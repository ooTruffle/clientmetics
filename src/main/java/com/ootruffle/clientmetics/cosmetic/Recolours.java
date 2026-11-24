package com.ootruffle.clientmetics.cosmetic;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.net.RecolourApi;
import com.ootruffle.clientmetics.platform.Game;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Colours Clientmetics users pick for the cosmetics they wear, shared through the Clientmetics
 * recolour server ({@link RecolourApi}) so every Clientmetics user sees them.
 * <p>
 * A cosmetic is named by its key: {@code lunar:<id>}, {@code dawn:<slug>} or {@code essential:<id>}
 * (the wardrobe's item keys). A colour only changes how a cosmetic is drawn: the renderers look one
 * up for each cosmetic Lunar, Dawn or Essential say the player is wearing, so it never shows
 * anything by itself.
 * <p>
 * Your own colours are kept in {@code config/clientmetics/colours.json}, and sent on the first sync
 * and after every change. While on a server the players around you are looked up as they appear,
 * and everyone again (which keeps your own presence alive) every {@link #HEARTBEAT_MS}. Network
 * work runs on one background thread.
 */
public final class Recolours {

    /** At most one request every 5 s (the server allows 30 a minute). */
    private static final int SYNC_INTERVAL_TICKS = 100;
    private static final long HEARTBEAT_MS = 30_000;
    private static final int MAX_LOOKUP = 512;
    public static final int MAX_COLOURS = 64;
    private static final long TOKEN_REFRESH_MARGIN_MS = 60_000;
    private static final long MIN_BACKOFF_MS = 10_000;
    private static final long MAX_BACKOFF_MS = 300_000;
    private static final Gson GSON = new Gson();

    /** Clientmetics users around us -> their colours (RGB) by cosmetic key. */
    private static final Map<UUID, Map<String, Integer>> REMOTE = new ConcurrentHashMap<>();
    /** The bell collar, this mod's own cosmetic: drawn by {@code BellCollar}. */
    public static final String BELL_COLLAR = "bell_collar";
    /** Its key for colours. */
    public static final String BELL_COLLAR_KEY = "clientmetics:" + BELL_COLLAR;

    /** Clientmetics users around us -> the extras they wear -> whether it jingles. */
    private static final Map<UUID, Map<String, Boolean>> REMOTE_WEAR = new ConcurrentHashMap<>();
    /** Our own colours, by cosmetic key. Copy-on-write so the render thread never sees a half-made change. */
    private static volatile Map<String, Integer> own = load();
    private static final AtomicReference<Set<UUID>> PENDING = new AtomicReference<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Clientmetics-Recolours");
        t.setDaemon(true);
        return t;
    });

    /** The logged-in account, read once a tick (the session builds a new profile on every call). */
    private static volatile UUID self;

    // Client thread only.
    private static boolean wasActive;
    private static int ticks;
    private static int selfTicks;

    // Worker thread only (ownDirty is set from the client thread).
    private static RecolourApi.Token token;
    private static final Set<UUID> looked = new HashSet<>();
    private static long lastSync;
    private static long nextAttempt;
    private static long backoff = MIN_BACKOFF_MS;
    private static volatile boolean ownDirty = true;
    private static volatile String status = "";

    private Recolours() {}

    /** The colour (RGB) this player picked for this cosmetic, or -1 for its own colours. Any thread. */
    public static int colour(UUID uuid, String key) {
        final Config config = Config.get();
        if (uuid == null || !config.enabled || !config.recolours) {
            return -1;
        }
        final Map<String, Integer> map = uuid.equals(self) ? own : REMOTE.get(uuid);
        final Integer rgb = map == null ? null : map.get(key);
        return rgb == null ? -1 : rgb & 0xFFFFFF;
    }

    /**
     * Whether this player wears the extra, and whether it jingles: null if they don't wear it,
     * else {@code TRUE} if it jingles. You wear it when it's been granted to you and is switched on.
     * Any thread.
     */
    public static Boolean wears(UUID uuid, String extra) {
        final Config config = Config.get();
        if (uuid == null || !config.enabled || !config.recolours) {
            return null;
        }
        if (uuid.equals(self)) {
            return granted(extra) && config.bellCollar ? config.bellJingle : null;
        }
        final Map<String, Boolean> extras = REMOTE_WEAR.get(uuid);
        return extras == null ? null : extras.get(extra);
    }

    /** Whether the colour server lets you wear this extra (as of the last sync). */
    public static boolean granted(String extra) {
        final java.util.List<String> list = Config.get().grantedExtras;
        return list != null && list.contains(extra);
    }

    /** Sends what you wear (after a switch changed) on the next sync. */
    public static void wearChanged() {
        Config.save();
        ownDirty = true;
    }

    /** Your own colour for this cosmetic, or null. */
    public static Integer own(String key) {
        return own.get(key);
    }

    /**
     * Sets (or with null, clears) your colour for this cosmetic, saves it and sends it on the next
     * sync. False if you already have {@link #MAX_COLOURS} and this would be a new one.
     */
    public static synchronized boolean setOwn(String key, Integer rgb) {
        final Map<String, Integer> next = new TreeMap<>(own);
        if (rgb == null) {
            if (next.remove(key) == null) {
                return true;
            }
        } else {
            if (!next.containsKey(key) && next.size() >= MAX_COLOURS) {
                return false;
            }
            next.put(key, rgb & 0xFFFFFF);
        }
        own = Collections.unmodifiableMap(next);
        save(next);
        ownDirty = true;
        return true;
    }

    /** What the last sync did, for the wardrobe ("" while it's fine). */
    public static String status() {
        return status;
    }

    public static int knownPlayers() {
        return REMOTE.size();
    }

    /** End of every client tick. */
    public static void onClientTick() {
        final Game game = Game.get();
        final Config config = Config.get();
        // You, as the world knows you (the same as the session's account, except in offline dev worlds).
        if (game.inWorld() && (self == null || ++selfTicks % 20 == 0)) {
            UUID you = null;
            for (Game.Player player : game.playersByDistance()) {
                if (player.self) {
                    you = player.uuid;
                    break;
                }
            }
            self = you != null ? you : game.sessionId();
        } else if (self == null) {
            self = game.sessionId();
        }
        final boolean active = config.enabled && config.recolours && config.termsNoticeSeen
                && game.inWorld() && !game.singleplayer();
        if (active != wasActive) {
            wasActive = active;
            ticks = SYNC_INTERVAL_TICKS; // Sync straight away.
            WORKER.execute(active ? Recolours::reset : Recolours::leave);
        }
        if (!active || ++ticks < SYNC_INTERVAL_TICKS) {
            return;
        }
        ticks = 0;
        final Set<UUID> visible = new LinkedHashSet<>();
        for (Game.Player player : game.playersByDistance()) {
            if (visible.size() >= MAX_LOOKUP) {
                break;
            }
            // Real accounts have v4 UUIDs; server NPCs can't run the mod.
            if (!player.self && player.uuid != null && player.uuid.version() == 4) {
                visible.add(player.uuid);
            }
        }
        if (PENDING.getAndSet(visible) == null) {
            WORKER.execute(Recolours::sync);
        }
    }

    private static void reset() {
        looked.clear();
        REMOTE.clear();
        REMOTE_WEAR.clear();
        lastSync = 0;
        ownDirty = true;
    }

    private static void leave() {
        PENDING.set(null);
        REMOTE.clear();
        REMOTE_WEAR.clear();
        looked.clear();
        final RecolourApi.Token t = token;
        if (t != null) {
            try {
                RecolourApi.leave(t);
            } catch (Exception e) {
                // Our presence runs out on its own in a minute and a half.
            }
        }
    }

    private static void sync() {
        final Set<UUID> visible = PENDING.getAndSet(null);
        if (visible == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now < nextAttempt) {
            return;
        }
        final boolean newPlayers = !looked.containsAll(visible);
        if (!newPlayers && !ownDirty && now - lastSync < HEARTBEAT_MS) {
            return;
        }
        try {
            if (token == null || token.expiresAt - TOKEN_REFRESH_MARGIN_MS < now) {
                token = RecolourApi.login(Game.get().sessionName());
                // The server keeps what we wear in memory only: a new login (say, after it
                // restarted) sends everything again.
                ownDirty = true;
            }
            // Cleared before sending: a change made while this is in flight goes out next time.
            final boolean sendOwn = ownDirty;
            ownDirty = false;
            final RecolourApi.SyncResult result;
            try {
                result = RecolourApi.sync(token, sendOwn ? own : null, sendOwn ? ownWear() : null, visible);
            } catch (Exception e) {
                if (sendOwn) {
                    ownDirty = true;
                }
                throw e;
            }
            final Map<UUID, Map<String, Integer>> found = result.colours;
            // Everyone looked up is answered for: anyone missing has no colours (or stopped the mod).
            REMOTE.keySet().retainAll(visible);
            REMOTE_WEAR.keySet().retainAll(visible);
            for (UUID uuid : visible) {
                final Map<String, Integer> map = found.get(uuid);
                if (map == null) {
                    REMOTE.remove(uuid);
                } else {
                    REMOTE.put(uuid, Collections.unmodifiableMap(map));
                }
                final Map<String, Boolean> extras = result.wear.get(uuid);
                if (extras == null) {
                    REMOTE_WEAR.remove(uuid);
                } else {
                    REMOTE_WEAR.put(uuid, Collections.unmodifiableMap(extras));
                }
            }
            final java.util.List<String> granted = new java.util.ArrayList<>(new java.util.TreeSet<>(result.granted));
            if (!granted.equals(Config.get().grantedExtras)) {
                final boolean before = granted(BELL_COLLAR);
                Config.get().grantedExtras = granted;
                Config.save();
                if (!before && granted(BELL_COLLAR)) {
                    ownDirty = true; // Just granted: send that we wear it.
                }
            }
            looked.clear();
            looked.addAll(visible);
            lastSync = now;
            backoff = MIN_BACKOFF_MS;
            status = "";
        } catch (RecolourApi.Unauthorized e) {
            token = null;
            nextAttempt = now + MIN_BACKOFF_MS;
        } catch (Exception e) {
            nextAttempt = now + backoff;
            if (backoff == MIN_BACKOFF_MS || Config.get().debug) {
                Clientmetics.LOGGER.warn("Recolour server sync failed, retrying in {}s: {}", backoff / 1000, e.toString());
            }
            backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
            status = "Couldn't reach the colour server; retrying.";
        }
    }

    /** What we wear, for the server: extra -> jingles. */
    private static Map<String, Boolean> ownWear() {
        final Config config = Config.get();
        final Map<String, Boolean> out = new TreeMap<>();
        if (config.bellCollar && granted(BELL_COLLAR)) {
            out.put(BELL_COLLAR, config.bellJingle);
        }
        return out;
    }

    // ---- colours.json ----

    private static Path file() {
        return Config.dir().resolve("colours.json");
    }

    private static Map<String, Integer> load() {
        final Path file = file();
        final Map<String, Integer> out = new TreeMap<>();
        try {
            if (Files.isRegularFile(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    final Map<String, String> read = GSON.fromJson(r, new TypeToken<LinkedHashMap<String, String>>() {}.getType());
                    if (read != null) {
                        for (Map.Entry<String, String> e : read.entrySet()) {
                            final Integer rgb = RecolourApi.parseHex(e.getValue());
                            if (rgb != null && out.size() < MAX_COLOURS) {
                                out.put(e.getKey(), rgb);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Clientmetics.LOGGER.warn("Couldn't read {}: {}", file, e.toString());
        }
        return Collections.unmodifiableMap(out);
    }

    private static void save(Map<String, Integer> colours) {
        final Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Integer> e : colours.entrySet()) {
            out.put(e.getKey(), RecolourApi.hex(e.getValue()));
        }
        final Path file = file();
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(out, w);
            }
        } catch (Exception e) {
            Clientmetics.LOGGER.warn("Couldn't save {}: {}", file, e.toString());
        }
    }

}
