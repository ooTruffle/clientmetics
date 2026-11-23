package com.ootruffle.clientmetics.cosmetic;

import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.asset.DawnCatalog;
import com.ootruffle.clientmetics.net.DawnAuthenticator;
import com.ootruffle.clientmetics.net.DawnSocket;
import com.ootruffle.clientmetics.render.CosmeticRenderer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Tracks nearby players on Dawn's gateway ({@code players.tracked}) and stores the slot-to-slug
 * outfit it sends back for each one, the same way {@link CosmeticManager} does for Lunar:
 * <ol>
 *     <li>Once a second the client thread snapshots nearby players.</li>
 *     <li>The worker thread diffs that against who's tracked and sends {@code track} frames with
 *     the players to add and remove, in batches.</li>
 *     <li>Snapshots (and later equip changes) replace that player's entry, which the renderer reads.</li>
 * </ol>
 * The connection is kept across world changes (swapping lobbies), so the Mojang session join that
 * signs in to Dawn only happens once per connection.
 */
public final class DawnCosmeticManager {

    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int MAX_BATCH = 100;
    private static final long MIN_BACKOFF_MS = 30_000;
    private static final long MAX_BACKOFF_MS = 300_000;
    private static final long STABLE_CONNECTION_MS = 300_000;

    private static final Map<UUID, DawnCosmeticSnapshot> OUTFITS = new ConcurrentHashMap<>();
    private static final AtomicReference<Set<UUID>> PENDING = new AtomicReference<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Clientmetics-Dawn");
        t.setDaemon(true);
        return t;
    });
    /** Written by the worker; read by the socket's thread when a snapshot arrives. */
    private static final Set<UUID> tracked = ConcurrentHashMap.newKeySet();

    // Client thread only.
    private static boolean wasActive;
    private static int ticks;

    // Worker thread only.
    private static volatile DawnSocket socket;
    private static volatile String token;
    private static long connectedAt;
    private static long nextConnectAttempt;
    private static long backoff = MIN_BACKOFF_MS;
    private static boolean warnedOffline;

    private DawnCosmeticManager() {}

    /** The player's Dawn outfit, or null if they aren't known to be on Dawn. */
    public static DawnCosmeticSnapshot outfit(UUID uuid) {
        return uuid == null ? null : OUTFITS.get(uuid);
    }

    /** Shows an outfit without the gateway (dev checks). */
    public static void showOutfit(DawnCosmeticSnapshot snapshot) {
        OUTFITS.put(snapshot.player, snapshot);
        CosmeticRenderer.preloadDawn(snapshot.equipped.values());
    }

    /** The access token the gateway connected with, or null; the wardrobe reuses it. */
    public static String token() {
        return token;
    }

    public static boolean isConnected() {
        final DawnSocket s = socket;
        return s != null && s.isOpen();
    }

    public static void onClientTick() {
        final Minecraft mc = Minecraft.getMinecraft();
        final Config config = Config.get();
        final boolean multiplayer = mc.theWorld != null && !mc.isSingleplayer();
        final boolean active = config.enabled && config.termsNoticeSeen && config.dawn && DawnCatalog.get() != null && mc.theWorld != null
                && (multiplayer || config.showOwn);
        if (active != wasActive) {
            wasActive = active;
            ticks = 0;
            if (!active) {
                OUTFITS.clear();
                WORKER.execute(DawnCosmeticManager::disconnect);
            }
        }
        if (!active || ++ticks < SYNC_INTERVAL_TICKS || mc.thePlayer == null) {
            return;
        }
        ticks = 0;

        final EntityPlayer self = mc.thePlayer;
        final List<EntityPlayer> players = new ArrayList<>(mc.theWorld.playerEntities);
        players.sort(Comparator.comparingDouble(p -> p.getDistanceSqToEntity(self)));
        final Set<UUID> visible = new LinkedHashSet<>();
        for (EntityPlayer player : players) {
            if (visible.size() >= Math.max(1, config.maxPlayers)) {
                break;
            }
            final UUID uuid = player.getUniqueID();
            // Real accounts have v4 UUIDs; server NPCs can't have a Dawn account.
            if (uuid == null || uuid.version() != 4 || (player == self && !config.showOwn)) {
                continue;
            }
            visible.add(uuid);
        }
        // Coalesce: if the worker is still busy (e.g. signing in), the newest snapshot wins.
        if (PENDING.getAndSet(visible) == null) {
            WORKER.execute(DawnCosmeticManager::sync);
        }
    }

    private static void disconnect() {
        if (socket != null) {
            socket.close();
            socket = null;
            Clientmetics.LOGGER.info("Disconnected from Dawn");
        }
        tracked.clear();
        OUTFITS.clear();
    }

    private static void sync() {
        final Set<UUID> visible = PENDING.getAndSet(null);
        if (visible == null || !ensureConnected()) {
            return;
        }
        final List<UUID> added = new ArrayList<>();
        for (UUID uuid : visible) {
            if (!tracked.contains(uuid)) {
                added.add(uuid);
            }
        }
        final List<UUID> removed = new ArrayList<>();
        for (UUID uuid : tracked) {
            if (!visible.contains(uuid)) {
                removed.add(uuid);
            }
        }
        tracked.removeAll(removed);
        tracked.addAll(added);
        for (UUID uuid : removed) {
            OUTFITS.remove(uuid);
        }
        for (int i = 0; i < Math.max(added.size(), removed.size()); i += MAX_BATCH) {
            socket.track(slice(added, i), slice(removed, i));
        }
    }

    private static List<UUID> slice(List<UUID> list, int from) {
        return from >= list.size() ? Collections.<UUID>emptyList() : list.subList(from, Math.min(from + MAX_BATCH, list.size()));
    }

    private static void store(DawnCosmeticSnapshot snapshot) {
        if (!tracked.contains(snapshot.player)) {
            return; // Left render range (or we disconnected) before the reply arrived.
        }
        OUTFITS.put(snapshot.player, snapshot);
        CosmeticRenderer.preloadDawn(snapshot.equipped.values());
        if (Config.get().debug) {
            Clientmetics.LOGGER.info("Dawn outfit for {}: {}", snapshot.player, snapshot.equipped);
        }
    }

    private static boolean ensureConnected() {
        if (socket != null && socket.isOpen()) {
            return true;
        }
        final long now = System.currentTimeMillis();
        if (socket != null) {
            Clientmetics.LOGGER.info("Dawn socket closed");
            if (now - connectedAt >= STABLE_CONNECTION_MS) {
                backoff = MIN_BACKOFF_MS;
            }
            socket = null;
            tracked.clear();
            OUTFITS.clear();
            scheduleRetry(now);
            return false;
        }
        if (now < nextConnectAttempt) {
            return false;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.getSession().getProfile().getId() == null || mc.getSession().getToken() == null
                || mc.getSession().getToken().length() < 32) {
            // Offline or dev accounts can't pass Mojang's session check, so don't keep trying.
            if (!warnedOffline) {
                warnedOffline = true;
                Clientmetics.LOGGER.warn("Not connecting to Dawn: this account isn't logged in to Microsoft (offline or dev session)");
            }
            nextConnectAttempt = now + MAX_BACKOFF_MS;
            return false;
        }
        try {
            final String jwt = DawnAuthenticator.fetchToken();
            token = jwt;
            socket = DawnSocket.connect(jwt, DawnCosmeticManager::store);
            connectedAt = System.currentTimeMillis();
            tracked.clear();
            Clientmetics.LOGGER.info("Connected to Dawn");
            return true;
        } catch (Exception e) {
            Clientmetics.LOGGER.warn("Couldn't connect to Dawn ({}), retrying in {}s", e.toString(), backoff / 1000);
            scheduleRetry(now);
            return false;
        }
    }

    private static void scheduleRetry(long now) {
        nextConnectAttempt = now + backoff;
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
    }

}
