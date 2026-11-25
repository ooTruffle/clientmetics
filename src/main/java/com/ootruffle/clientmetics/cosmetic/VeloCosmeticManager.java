package com.ootruffle.clientmetics.cosmetic;

import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.asset.VeloFiles;
import com.ootruffle.clientmetics.net.VeloApi;
import com.ootruffle.clientmetics.platform.Game;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Velo Client capes. Velo has no per-player lookup: every Velo client downloads one worldwide list
 * of who is online and which cape they wear ({@code GET /v1/online}, no sign-in), so this does the
 * same, every 30 seconds while you're on a server. Players are matched by UUID only, not by name
 * (Velo's name fallback would hand a Velo user's cape to anyone using their name on an offline-mode
 * server).
 * <p>
 * Your own cape comes from your local Velo folder, as in Velo, since this mod never sends Velo a
 * heartbeat and so never appears in that list itself.
 * <p>
 * On 1.8.9 Velo only draws capes (wings, auras and hats are reported but have no renderer), and the
 * list only carries capes, so capes are all there is to show.
 */
public final class VeloCosmeticManager {

    private static final int POLL_INTERVAL_TICKS = 30 * 20;
    private static final int LOCAL_INTERVAL_TICKS = 2 * 20;
    private static final long MIN_BACKOFF_MS = 60_000;
    private static final long MAX_BACKOFF_MS = 600_000;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Clientmetics-Velo");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    /** UUID to cape texture path. Replaced whole by the worker. */
    private static volatile Map<UUID, String> capes = Collections.emptyMap();
    /** Your own equipped cape from your Velo folder, or null. */
    private static volatile String ownCape;
    private static volatile UUID ownUuid;

    // Client thread only.
    private static volatile boolean wasPolling;
    private static int pollTicks;
    private static int localTicks = LOCAL_INTERVAL_TICKS;

    // Worker thread only.
    private static long nextPoll;
    private static long backoff = MIN_BACKOFF_MS;
    private static boolean loggedFirst;

    /** Capes shown without Velo's list (dev checks). */
    private static final Map<UUID, String> DEV = new java.util.concurrent.ConcurrentHashMap<>();

    private VeloCosmeticManager() {}

    /** Shows a cape texture path ({@code velo:...}) on a player without Velo's list, or removes it with null (dev checks). */
    public static void showCape(UUID uuid, String path) {
        if (path == null) {
            DEV.remove(uuid);
        } else {
            DEV.put(uuid, path);
        }
    }

    /** The cape texture path for this player, or null if they aren't known to wear a Velo cape. */
    public static String cape(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        final String dev = DEV.isEmpty() ? null : DEV.get(uuid);
        if (dev != null) {
            return dev;
        }
        if (uuid.equals(ownUuid) && ownCape != null) {
            return ownCape;
        }
        return capes.get(uuid);
    }

    public static void onClientTick() {
        final Game game = Game.get();
        final Config config = Config.get();
        final boolean on = config.enabled && config.termsNoticeSeen && config.veloOn() && config.showCloaks;
        final boolean inWorld = game.inWorld();
        final boolean polling = on && inWorld && !game.singleplayer();
        if (polling != wasPolling) {
            wasPolling = polling;
            pollTicks = POLL_INTERVAL_TICKS; // Poll straight away on joining a server.
            if (!polling) {
                capes = Collections.emptyMap();
            }
        }
        if (on && inWorld && config.showOwn) {
            if (++localTicks >= LOCAL_INTERVAL_TICKS) {
                localTicks = 0;
                // The cape goes on the player entity you are, which can have another UUID than the
                // session (offline and dev sessions get an offline-mode UUID); the account's Velo
                // config is found by the session's.
                UUID self = null;
                for (Game.Player player : game.playersByDistance()) {
                    if (player.self) {
                        self = player.uuid;
                        break;
                    }
                }
                final UUID entity = self;
                final UUID account = game.sessionId() != null ? game.sessionId() : self;
                WORKER.execute(() -> {
                    ownCape = VeloFiles.localCapePath(account);
                    ownUuid = entity;
                });
            }
        } else if (ownCape != null) {
            ownCape = null;
        }
        if (polling && ++pollTicks >= POLL_INTERVAL_TICKS) {
            pollTicks = 0;
            if (BUSY.compareAndSet(false, true)) {
                WORKER.execute(VeloCosmeticManager::poll);
            }
        }
    }

    private static void poll() {
        try {
            final long now = System.currentTimeMillis();
            if (now < nextPoll) {
                return;
            }
            final Map<UUID, String> online = VeloApi.online();
            final Map<UUID, String> paths = new java.util.HashMap<>();
            for (Map.Entry<UUID, String> e : online.entrySet()) {
                final String path = VeloFiles.capePath(e.getValue());
                if (path != null) {
                    paths.put(e.getKey(), path);
                }
            }
            if (wasPolling) {
                capes = Collections.unmodifiableMap(paths);
            }
            backoff = MIN_BACKOFF_MS;
            if (!loggedFirst || Config.get().debug) {
                loggedFirst = true;
                Clientmetics.LOGGER.info("Velo: {} users online, {} with a cape", online.size(), paths.size());
            }
        } catch (Exception e) {
            Clientmetics.LOGGER.warn("Couldn't read Velo's online list ({}), retrying in {}s", e.toString(), backoff / 1000);
            nextPoll = System.currentTimeMillis() + backoff;
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        } finally {
            BUSY.set(false);
        }
    }

}
