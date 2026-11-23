package com.ootruffle.clientmetics.cosmetic;

import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.asset.Catalog;
import com.ootruffle.clientmetics.compat.ClientTagCompat;
import com.ootruffle.clientmetics.net.LunarAuthenticator;
import com.ootruffle.clientmetics.net.LunarSocket;
import com.ootruffle.clientmetics.net.SessionJoins;
import com.ootruffle.clientmetics.render.CosmeticRenderer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import com.ootruffle.clientmetics.platform.Game;

/**
 * Keeps track of who is in render range, subscribes to them on Lunar's websocket, and stores the
 * outfits Lunar pushes back:
 * <ol>
 *     <li>Every {@link #SYNC_INTERVAL_TICKS} ticks the client thread snapshots nearby players.</li>
 *     <li>The worker thread diffs that against who's subscribed, and sends {@code SubscribeV2}
 *     for new players and {@code Unsubscribe} for ones that left, in batches.</li>
 *     <li>Replies and later pushes replace that player's entry in {@link #OUTFITS}, which the
 *     renderer reads.</li>
 * </ol>
 * Network work never runs on the client thread. Everything is cleared when the mod stops being
 * active (disconnect, turned off); a world change only drops the players who are no longer there.
 */
public final class CosmeticManager {

    private static final int SYNC_INTERVAL_TICKS = 4;
    private static final int MAX_BATCH = 100;
    private static final long MIN_BACKOFF_MS = 10_000;
    private static final long MAX_BACKOFF_MS = 300_000;
    private static final long STABLE_CONNECTION_MS = 300_000;

    private static final Map<UUID, List<EquippedCosmetic>> OUTFITS = new ConcurrentHashMap<>();
    private static final AtomicReference<Set<UUID>> PENDING = new AtomicReference<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Clientmetics-Lunar");
        t.setDaemon(true);
        return t;
    });

    // Client thread only.
    private static boolean wasActive;
    private static int ticks;

    // Worker thread only (generation is also read by response callbacks); socket is also read by the wardrobe.
    private static volatile LunarSocket socket;
    private static long connectedAt;
    private static long nextConnectAttempt;
    private static long backoff = MIN_BACKOFF_MS;
    private static boolean warnedOffline;
    private static final Set<UUID> subscribed = new HashSet<>();
    private static volatile int generation;

    // The last outfit known for the logged-in account (any thread).
    private static volatile List<EquippedCosmetic> ownOutfit;
    private static volatile UUID ownUuid;

    private CosmeticManager() {}

    /** The player's equipped cosmetics, or an empty list if they aren't known to be on Lunar. */
    public static List<EquippedCosmetic> outfit(UUID uuid) {
        final List<EquippedCosmetic> outfit = OUTFITS.get(uuid);
        if (outfit != null) {
            return outfit;
        }
        // Your own outfit outlives resets and reconnects, so it doesn't vanish until Lunar sends it again.
        final List<EquippedCosmetic> own = ownOutfit;
        if (own != null && uuid != null && uuid.equals(ownUuid) && Config.get().showOwn) {
            return own;
        }
        return Collections.<EquippedCosmetic>emptyList();
    }

    private static void rememberOwn(UUID uuid, List<EquippedCosmetic> outfit) {
        if (uuid != null && uuid.equals(Game.get().sessionId())) {
            ownOutfit = outfit;
            ownUuid = uuid;
        }
    }

    public static int knownPlayers() {
        return OUTFITS.size();
    }

    public static boolean isConnected() {
        final LunarSocket s = socket;
        return s != null && s.isOpen();
    }

    /** The open socket, or null. Its calls are safe from any thread. */
    public static LunarSocket socket() {
        final LunarSocket s = socket;
        return s != null && s.isOpen() ? s : null;
    }

    /** Shows an outfit right away (e.g. our own after the wardrobe changes it), before Lunar's push arrives. */
    public static void showOutfit(UUID uuid, List<EquippedCosmetic> outfit) {
        final List<EquippedCosmetic> copy = Collections.unmodifiableList(new ArrayList<>(outfit));
        OUTFITS.put(uuid, copy);
        rememberOwn(uuid, copy);
        CosmeticRenderer.preload(outfit);
    }

    public static void onClientTick() {
        final Game game = Game.get();
        final Config config = Config.get();
        final boolean inWorld = game.inWorld();
        final boolean multiplayer = inWorld && !game.singleplayer();
        final boolean active = config.enabled && config.lunarOn() && config.termsNoticeSeen && Catalog.get() != null && inWorld
                && (multiplayer || config.showOwn);

        // A world change alone (swapping lobbies) resets nothing: the next sync drops whoever is
        // gone, and players still here (always yourself) keep their subscription and outfit.
        // Unsubscribing and resubscribing the same player straight away loses their outfit.
        if (active != wasActive) {
            wasActive = active;
            ticks = 0;
            OUTFITS.clear();
            WORKER.execute(active ? CosmeticManager::resetSubscriptions : CosmeticManager::disconnect);
        }
        if (!active || ++ticks < SYNC_INTERVAL_TICKS) {
            return;
        }
        ticks = 0;

        final Set<UUID> visible = new LinkedHashSet<>();
        for (Game.Player player : game.playersByDistance()) {
            if (visible.size() >= Math.max(1, config.maxPlayers)) {
                break;
            }
            final UUID uuid = player.uuid;
            final boolean isSelf = player.self;
            // Real accounts have v4 UUIDs; server NPCs (v2 on Hypixel) can't be on Lunar.
            if (uuid == null || uuid.version() != 4 || (isSelf && !config.showOwn)) {
                continue;
            }
            if (!isSelf && ClientTagCompat.isPresent() && ClientTagCompat.knownNotOnLunar(uuid)) {
                continue;
            }
            visible.add(uuid);
        }
        // Coalesce: if the worker is still busy (e.g. logging in), the newest snapshot wins.
        if (PENDING.getAndSet(visible) == null) {
            WORKER.execute(CosmeticManager::sync);
        }
    }

    private static void resetSubscriptions() {
        generation++;
        if (socket != null && socket.isOpen() && !subscribed.isEmpty()) {
            socket.unsubscribe(new ArrayList<>(subscribed));
        }
        subscribed.clear();
        OUTFITS.clear();
    }

    private static void disconnect() {
        generation++;
        if (socket != null) {
            socket.close();
            socket = null;
            Clientmetics.LOGGER.info("Disconnected from Lunar");
        }
        subscribed.clear();
        OUTFITS.clear();
    }

    private static void sync() {
        final Set<UUID> visible = PENDING.getAndSet(null);
        if (visible == null || !ensureConnected()) {
            return;
        }
        final List<UUID> added = new ArrayList<>();
        for (UUID uuid : visible) {
            if (!subscribed.contains(uuid)) {
                added.add(uuid);
            }
        }
        final List<UUID> removed = new ArrayList<>();
        for (UUID uuid : subscribed) {
            if (!visible.contains(uuid)) {
                removed.add(uuid);
            }
        }
        if (!removed.isEmpty()) {
            subscribed.removeAll(removed);
            for (UUID uuid : removed) {
                OUTFITS.remove(uuid);
            }
            for (int i = 0; i < removed.size(); i += MAX_BATCH) {
                socket.unsubscribe(new ArrayList<>(removed.subList(i, Math.min(i + MAX_BATCH, removed.size()))));
            }
        }
        for (int i = 0; i < added.size(); i += MAX_BATCH) {
            subscribe(new ArrayList<>(added.subList(i, Math.min(i + MAX_BATCH, added.size()))));
        }
    }

    private static void subscribe(List<UUID> batch) {
        subscribed.addAll(batch);
        final int gen = generation;
        socket.subscribe(batch).whenComplete((pushes, error) -> {
            if (gen != generation) {
                return;
            }
            if (error != null) {
                Clientmetics.LOGGER.warn("SubscribeV2 failed: {}", error.toString());
                // Let the next sync try these players again.
                WORKER.execute(() -> {
                    if (gen == generation) {
                        subscribed.removeAll(batch);
                    }
                });
                return;
            }
            for (LunarSocket.Push push : pushes) {
                store(push);
            }
            if (Config.get().debug) {
                Clientmetics.LOGGER.info("SubscribeV2: {} players, {} on Lunar", batch.size(), pushes.size());
            }
        });
    }

    private static void store(LunarSocket.Push push) {
        final List<EquippedCosmetic> copy = Collections.unmodifiableList(new ArrayList<>(push.cosmetics));
        OUTFITS.put(push.player, copy);
        rememberOwn(push.player, copy);
        CosmeticRenderer.preload(push.cosmetics);
        if (Config.get().debug) {
            final StringBuilder ids = new StringBuilder();
            for (EquippedCosmetic c : push.cosmetics) {
                ids.append(ids.length() == 0 ? "" : ", ").append(c.id);
            }
            Clientmetics.LOGGER.info("Outfit for {}: [{}]", push.player, ids);
        }
    }

    private static boolean ensureConnected() {
        if (socket != null && socket.isOpen()) {
            return true;
        }
        final long now = System.currentTimeMillis();
        if (socket != null) {
            Clientmetics.LOGGER.info("Lunar socket closed");
            if (now - connectedAt >= STABLE_CONNECTION_MS) {
                backoff = MIN_BACKOFF_MS;
            }
            socket = null;
            subscribed.clear();
            OUTFITS.clear();
            scheduleRetry(now);
            return false;
        }
        if (now < nextConnectAttempt) {
            return false;
        }
        final Game game = Game.get();
        final UUID uuid = game.sessionId();
        final String name = game.sessionName();
        final String token = game.sessionToken();
        if (uuid == null || token == null || token.length() < 32) {
            // Offline/cracked or dev accounts can't pass Mojang's session check, so don't keep trying.
            if (!warnedOffline) {
                warnedOffline = true;
                Clientmetics.LOGGER.warn("Not connecting to Lunar: this account isn't logged in to Microsoft (offline or dev session)");
            }
            nextConnectAttempt = now + MAX_BACKOFF_MS;
            return false;
        }
        try {
            final String jwt = LunarAuthenticator.fetchToken(uuid, name, SessionJoins::join);
            final LunarSocket connected = LunarSocket.connect(uuid, name, jwt, Config.installationId());
            final int gen = generation;
            connected.onPush(push -> {
                if (gen == generation && subscribed.contains(push.player)) {
                    store(push);
                }
            });
            socket = connected;
            connectedAt = System.currentTimeMillis();
            subscribed.clear();
            Clientmetics.LOGGER.info("Connected to Lunar");
            return true;
        } catch (Exception e) {
            Clientmetics.LOGGER.warn("Couldn't connect to Lunar ({}), retrying in {}s", e.toString(), backoff / 1000);
            scheduleRetry(now);
            return false;
        }
    }

    private static void scheduleRetry(long now) {
        nextConnectAttempt = now + backoff;
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
    }

}
