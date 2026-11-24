// The Clientmetics recolour server: Clientmetics users pick a colour for the cosmetics they wear,
// and look up the colours of the Clientmetics users around them. No dependencies - Node 18+ only.
//
//   POST /v1/login  { name, serverId }  -> { token, uuid, expiresIn }
//        after the client did a Mojang session server join with serverId (checked via hasJoined)
//   POST /v1/sync   { colours?: { key: "#rrggbb" }, wear?: { extra: { jingle } }, lookup: [uuid] }
//                   -> { colours: { uuid: { key: "#rrggbb" } }, wear: { uuid: { extra: { jingle } } }, granted: [extra] }
//        (Authorization: Bearer <token>) - refreshes our presence; when colours is sent it replaces
//        all of ours, and when wear is sent it replaces what we wear (only extras granted to us count).
//        Answers only for players whose mod is live (synced in the last PRESENCE_TTL_MS).
//   POST /v1/leave  -> drops our presence
//   GET  /health    -> ok
//
// A key names a cosmetic: "lunar:<id>", "dawn:<slug>" or "essential:<id>". The mod only applies a
// colour to a cosmetic the player is really wearing (as Lunar, Dawn or Essential report it), so a
// colour on its own never shows anything.
//
// Extras are this mod's own cosmetics (made for Clientmetics, not copies of anyone's), handed out by
// hand in GRANTS_FILE: { uuid: ["bell_collar"] }. It's read again within 30 s of being changed.
//
// Written to disk: each player's chosen colours, in COLOURS_FILE. Nothing else (no names, no IPs).
// What players wear is kept in memory; the mod sends it again after logging in.

import { createServer } from "node:http";
import { randomBytes } from "node:crypto";
import { readFileSync, renameSync, statSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const HOST = process.env.HOST ?? "127.0.0.1";
const PORT = Number(process.env.PORT ?? 8788);
// Behind a Cloudflare Tunnel every request comes from cloudflared, so the real address is in
// CF-Connecting-IP. It's only believed from TRUSTED_PROXIES (this machine by default).
const TRUST_CF = (process.env.TRUST_CF ?? "1") === "1";
const TRUSTED_PROXIES = new Set(["127.0.0.1", "::1", ...(process.env.TRUSTED_PROXIES ?? "").split(",")]
  .map((ip) => ip.trim())
  .filter(Boolean)
  .flatMap((ip) => (ip.includes(":") ? [ip] : [ip, `::ffff:${ip}`])));
const COLOURS_FILE = process.env.COLOURS_FILE ?? fileURLToPath(new URL("./colours.json", import.meta.url));
const GRANTS_FILE = process.env.GRANTS_FILE ?? fileURLToPath(new URL("./grants.json", import.meta.url));
const EXTRAS = new Set(["bell_collar"]);

const TOKEN_TTL_MS = 6 * 60 * 60 * 1000;
const PRESENCE_TTL_MS = 90 * 1000; // clients sync every 30 s
const SERVER_ID_TTL_MS = 5 * 60 * 1000;
const MAX_BODY = 64 * 1024;
const MAX_LOOKUP = 512;
const MAX_COLOURS = 64;
const KEY_RE = /^(lunar:\d{1,10}|dawn:[A-Za-z0-9_.-]{1,64}|essential:[A-Za-z0-9_.-]{1,64}|clientmetics:[a-z_]{1,32})$/;
const COLOUR_RE = /^#[0-9a-f]{6}$/;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

// token -> { uuid, expires }
const tokens = new Map();
// uuid -> expires: players whose mod is running right now
const presence = new Map();
// uuid -> { key: "#rrggbb" }; kept in COLOURS_FILE
const colours = loadColours();
let saveTimer = null;
// uuid -> { extra: { jingle } }: the extras live players are wearing
const wear = new Map();
// uuid -> Set of extras they may wear; kept in GRANTS_FILE, edited by hand
let grants = new Map();
let grantsMtime = -1;
// serverIds already used for a login, so a join can't be replayed
const usedServerIds = new Map();
// rate limit buckets: key -> { count, resetAt }
const buckets = new Map();

function loadColours() {
  try {
    const data = JSON.parse(readFileSync(COLOURS_FILE, "utf8"));
    const out = new Map();
    for (const [uuid, map] of Object.entries(data ?? {})) {
      const clean = cleanColours(map);
      if (UUID_RE.test(uuid) && clean) out.set(uuid, clean);
    }
    return out;
  } catch (e) {
    if (e.code !== "ENOENT") console.error(`Couldn't read ${COLOURS_FILE}:`, e);
    return new Map();
  }
}

// Reads GRANTS_FILE again if it changed. Unknown extras and bad UUIDs are ignored.
function loadGrants() {
  let mtime;
  try {
    mtime = statSync(GRANTS_FILE).mtimeMs;
  } catch (e) {
    if (e.code === "ENOENT") {
      grants = new Map();
      grantsMtime = -1;
    }
    return;
  }
  if (mtime === grantsMtime) return;
  try {
    const data = JSON.parse(readFileSync(GRANTS_FILE, "utf8"));
    const next = new Map();
    for (const [raw, list] of Object.entries(data ?? {})) {
      const uuid = raw.toLowerCase();
      if (!UUID_RE.test(uuid) || !Array.isArray(list)) continue;
      const extras = new Set(list.filter((id) => EXTRAS.has(id)));
      if (extras.size) next.set(uuid, extras);
    }
    grants = next;
    grantsMtime = mtime;
    console.log(`Loaded ${GRANTS_FILE}: ${next.size} player(s) with extras`);
  } catch (e) {
    console.error(`Couldn't read ${GRANTS_FILE} (keeping the last good copy):`, e.message);
    grantsMtime = mtime;
  }
}

// The extras a player sent that they're granted, as { extra: { jingle } }, or null for none.
function cleanWear(uuid, body) {
  const granted = grants.get(uuid);
  if (!granted || !body || typeof body !== "object" || Array.isArray(body)) return null;
  const out = {};
  let n = 0;
  for (const [id, settings] of Object.entries(body)) {
    if (!granted.has(id)) continue;
    out[id] = { jingle: settings?.jingle !== false };
    n++;
  }
  return n ? out : null;
}

// Batched, and written to a temporary file first so a crash mid-write can't lose everything.
function saveColours() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try {
      const tmp = `${COLOURS_FILE}.tmp`;
      writeFileSync(tmp, JSON.stringify(Object.fromEntries(colours)));
      renameSync(tmp, COLOURS_FILE);
    } catch (e) {
      console.error(`Couldn't write ${COLOURS_FILE}:`, e);
    }
  }, 5_000);
}

// The valid entries of a { key: "#rrggbb" } object (at most MAX_COLOURS), or null if there are none.
function cleanColours(map) {
  if (!map || typeof map !== "object" || Array.isArray(map)) return null;
  const out = {};
  let n = 0;
  for (const [key, value] of Object.entries(map)) {
    if (n >= MAX_COLOURS) break;
    if (typeof value !== "string") continue;
    const colour = value.toLowerCase();
    if (KEY_RE.test(key) && COLOUR_RE.test(colour)) {
      out[key] = colour;
      n++;
    }
  }
  return n ? out : null;
}

function limited(key, max, windowMs) {
  const now = Date.now();
  let bucket = buckets.get(key);
  if (!bucket || now >= bucket.resetAt) {
    bucket = { count: 0, resetAt: now + windowMs };
    buckets.set(key, bucket);
  }
  return ++bucket.count > max;
}

function dashed(id) {
  return `${id.slice(0, 8)}-${id.slice(8, 12)}-${id.slice(12, 16)}-${id.slice(16, 20)}-${id.slice(20)}`.toLowerCase();
}

async function login(body, ip) {
  if (limited(`login:${ip}`, 10, 60_000)) return [429, { error: "slow down" }];
  const { name, serverId } = body;
  if (typeof name !== "string" || !/^\w{1,16}$/.test(name)) return [400, { error: "bad name" }];
  if (typeof serverId !== "string" || !/^[A-Za-z]{32}$/.test(serverId)) return [400, { error: "bad serverId" }];
  if (usedServerIds.has(serverId)) return [401, { error: "serverId already used" }];
  usedServerIds.set(serverId, Date.now() + SERVER_ID_TTL_MS);

  const url = `https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(name)}&serverId=${serverId}`;
  const response = await fetch(url, { signal: AbortSignal.timeout(10_000) });
  if (response.status !== 200) return [401, { error: "session not verified" }];
  const profile = await response.json();
  if (typeof profile?.id !== "string" || !/^[0-9a-fA-F]{32}$/.test(profile.id)) return [502, { error: "bad Mojang response" }];

  const uuid = dashed(profile.id);
  const token = randomBytes(32).toString("base64url");
  tokens.set(token, { uuid, expires: Date.now() + TOKEN_TTL_MS });
  return [200, { token, uuid, expiresIn: TOKEN_TTL_MS / 1000 }];
}

function sync(session, body) {
  if (limited(`sync:${session.uuid}`, 30, 60_000)) return [429, { error: "slow down" }];
  const now = Date.now();
  presence.set(session.uuid, now + PRESENCE_TTL_MS);
  if (body.colours !== undefined) {
    const clean = cleanColours(body.colours);
    if (clean) colours.set(session.uuid, clean);
    else colours.delete(session.uuid);
    saveColours();
  }
  if (body.wear !== undefined) {
    const clean = cleanWear(session.uuid, body.wear);
    if (clean) wear.set(session.uuid, clean);
    else wear.delete(session.uuid);
  }

  const out = {};
  const worn = {};
  if (Array.isArray(body.lookup)) {
    for (const raw of body.lookup.slice(0, MAX_LOOKUP)) {
      if (typeof raw !== "string") continue;
      const uuid = raw.toLowerCase();
      if (!UUID_RE.test(uuid) || uuid === session.uuid) continue;
      const live = presence.get(uuid);
      if (!live || live <= now) continue;
      const map = colours.get(uuid);
      if (map) out[uuid] = map;
      // Checked again here, so taking a grant away hides it straight away.
      const extras = wear.get(uuid);
      const granted = grants.get(uuid);
      if (extras && granted) {
        const allowed = Object.fromEntries(Object.entries(extras).filter(([id]) => granted.has(id)));
        if (Object.keys(allowed).length) worn[uuid] = allowed;
      }
    }
  }
  return [200, { colours: out, wear: worn, granted: [...(grants.get(session.uuid) ?? [])] }];
}

function authenticate(req) {
  const header = req.headers.authorization ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : null;
  const session = token && tokens.get(token);
  if (!session || session.expires <= Date.now()) return null;
  return session;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY) {
        reject(new Error("too large"));
        req.destroy();
      } else {
        chunks.push(chunk);
      }
    });
    req.on("end", () => {
      try {
        const text = Buffer.concat(chunks).toString("utf8");
        resolve(text ? JSON.parse(text) : {});
      } catch (e) {
        reject(e);
      }
    });
    req.on("error", reject);
  });
}

function send(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(text) });
  res.end(text);
}

const server = createServer(async (req, res) => {
  const viaTunnel = TRUSTED_PROXIES.has(req.socket.remoteAddress);
  const ip = (TRUST_CF && viaTunnel && req.headers["cf-connecting-ip"]) || req.socket.remoteAddress;
  const path = (req.url ?? "/").split("?")[0];
  try {
    if (req.method === "GET" && path === "/health") return send(res, 200, { ok: true });
    if (req.method !== "POST") return send(res, 404, { error: "not found" });

    let body;
    try {
      body = await readBody(req);
    } catch {
      return send(res, 400, { error: "bad body" });
    }
    if (typeof body !== "object" || body === null) return send(res, 400, { error: "bad body" });

    if (path === "/v1/login") return send(res, ...(await login(body, ip)));

    const session = authenticate(req);
    if (!session) return send(res, 401, { error: "unauthorized" });
    if (path === "/v1/sync") return send(res, ...sync(session, body));
    if (path === "/v1/leave") {
      presence.delete(session.uuid);
      wear.delete(session.uuid);
      return send(res, 200, {});
    }
    return send(res, 404, { error: "not found" });
  } catch (e) {
    console.error(`${req.method} ${req.url} failed:`, e);
    if (!res.headersSent) send(res, 500, { error: "internal error" });
  }
});

// Drop everything that has expired once a minute.
setInterval(() => {
  const now = Date.now();
  for (const [key, value] of tokens) if (value.expires <= now) tokens.delete(key);
  for (const [key, expires] of presence) {
    if (expires <= now) {
      presence.delete(key);
      wear.delete(key);
    }
  }
  for (const [key, expires] of usedServerIds) if (expires <= now) usedServerIds.delete(key);
  for (const [key, bucket] of buckets) if (bucket.resetAt <= now) buckets.delete(key);
}, 60_000).unref();

loadGrants();
setInterval(loadGrants, 30_000).unref();

server.listen(PORT, HOST, () => {
  console.log(`Clientmetics recolour server listening on http://${HOST}:${PORT}`);
});
