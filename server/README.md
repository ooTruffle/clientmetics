# Clientmetics recolour server

Clientmetics users pick a colour for cosmetics they wear (in the wardrobe), and look up the
colours of the Clientmetics users around them, so everyone with the mod sees the same colours.
It's separate from the ClientTag server and doesn't need it.

One file, no dependencies, Node 18 or newer:

```bash
node server.mjs
```

| Variable   | Default     | Meaning |
|------------|-------------|---------|
| `HOST`     | `127.0.0.1` | Address to listen on. |
| `PORT`     | `8788`      | Port to listen on (one above ClientTag's, so both can run on one machine). |
| `TRUST_CF` | `1`         | Read the client IP from `CF-Connecting-IP` (for login rate limits), only on requests from cloudflared - see `TRUSTED_PROXIES`. |
| `TRUSTED_PROXIES` | (unset) | Comma-separated IPs of other machines running cloudflared for this server. This machine is always trusted. |
| `COLOURS_FILE` | `colours.json` next to `server.mjs` | Each player's chosen colours, by UUID. The only thing written to disk. |

Tokens and who's online are kept in memory; a restart just means everyone logs in again on
their next sync.

## Running it behind a Cloudflare Tunnel

Add a hostname to the tunnel's ingress, before the catch-all:

```yaml
ingress:
  - hostname: clientmetics.fluffykiwi.net
    service: http://127.0.0.1:8788
  - service: http_status:404
```

The mod uses `https://clientmetics.fluffykiwi.net` (`RecolourApi.DEFAULT_URL`); launch with
`-Dclientmetics.server=http://127.0.0.1:8788` to test against a local copy instead.

## Protocol

| Request | Body | Response |
|---|---|---|
| `POST /v1/login` | `{ name, serverId }`, sent after a Mojang session join with `serverId` | `{ token, uuid, expiresIn }` |
| `POST /v1/sync` (Bearer token) | `{ colours?: { key: "#rrggbb" }, lookup: [uuid, ...] }` (at most 512 lookups, 64 colours) | `{ colours: { uuid: { key: "#rrggbb" } } }` |
| `POST /v1/leave` (Bearer token) | `{}` | `{}` |
| `GET /health` | | `{ ok: true }` |

A key names a cosmetic: `lunar:<id>`, `dawn:<slug>` or `essential:<id>`. When `colours` is sent
it replaces all of the caller's colours (`{}` clears them); the mod sends it only after a change
and on its first sync. Keys or colours in the wrong shape are dropped.

Only players whose mod synced in the last 90 seconds are answered for, so someone who stops
using the mod stops showing colours. A colour does nothing on its own: the mod only applies it to
a cosmetic that Lunar, Dawn or Essential say the player is wearing.

A sync refreshes your presence for 90 seconds. The mod syncs at most every 5 seconds (when new
players come into range) and at least every 30 while you're on a server, and leaves when you
disconnect. Logins are limited to 10 a minute per IP and syncs to 30 a minute per player.
