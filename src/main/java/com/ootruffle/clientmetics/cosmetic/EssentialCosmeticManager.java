package com.ootruffle.clientmetics.cosmetic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.asset.EssentialCatalog;
import com.ootruffle.clientmetics.net.EssentialSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Asks Essential's Connection Manager what nearby players are wearing, and keeps the answer.
 *
 * <p>Essential has no HTTP endpoint for one player's outfit, so this mirrors its own client: sign in
 * once, then ask per player as they come into range. Replies and live changes both land in
 * {@link #OUTFITS}, which the renderer reads.
 *
 * <p>Off unless {@code essential} is turned on, and idle until Essential has sent a catalogue.
 * Everything runs on one worker thread; a failed connection backs off and retries.
 */
public final class EssentialCosmeticManager {

    private static final int SYNC_INTERVAL_TICKS = 20;
    /** Ticks between attempts to bring the socket up while it is down (it backs off internally). */
    private static final int CONNECT_RETRY_TICKS = 40;
    private static final int MAX_REQUESTS_PER_SYNC = 20;
    /** Asked players are not asked again for this long, so a missing reply doesn't loop. */
    private static final long REQUEST_TTL_MS = 30_000;
    private static final long MIN_BACKOFF_MS = 30_000;
    private static final long MAX_BACKOFF_MS = 300_000;
    private static final long STABLE_CONNECTION_MS = 300_000;

    private static final Map<UUID, EssentialSnapshot> OUTFITS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> REQUESTED = new ConcurrentHashMap<>();
    private static final AtomicReference<Set<UUID>> PENDING = new AtomicReference<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Clientmetics-Essential");
        t.setDaemon(true);
        return t;
    });
    /** Written by the worker; read by the socket's thread. */
    private static final Set<UUID> tracked = ConcurrentHashMap.newKeySet();

    /** The outfit Essential has selected for this account, and the cosmetics it reports as owned. */
    private static volatile String selectedOutfitId;
    private static final Set<String> owned = ConcurrentHashMap.newKeySet();

    // Client thread only.
    private static boolean wasWanted;
    private static int ticks;
    private static int connectTicks;
    private static boolean loggedWhy;

    // Worker thread.
    private static volatile EssentialSocket socket;
    private static long connectedAt;
    private static long nextConnectAttempt;
    private static long backoff = MIN_BACKOFF_MS;
    private static boolean warnedOffline;

    /** Why Essential support is currently idle, for the wardrobe and the log; empty when connected. */
    private static volatile String status = "";

    private EssentialCosmeticManager() {}

    /** The player's Essential outfit, or null if they aren't known to be on Essential. */
    public static EssentialSnapshot outfit(UUID uuid) {
        return uuid == null ? null : OUTFITS.get(uuid);
    }

    /** Dev only (see DevAutotest): shows an outfit on a player without Essential sending it. */
    public static void showOutfit(EssentialSnapshot snapshot) {
        OUTFITS.put(snapshot.player, snapshot);
        com.ootruffle.clientmetics.render.CosmeticRenderer.preloadEssential(snapshot);
    }

    public static boolean isConnected() {
        final EssentialSocket s = socket;
        return s != null && s.isOpen();
    }

    public static String status() {
        return status;
    }

    /** How many players currently have a known outfit, for diagnostics. */
    public static int knownPlayers() {
        return OUTFITS.size();
    }

    public static void onClientTick() {
        final Minecraft mc = Minecraft.getMinecraft();
        final Config config = Config.get();
        final boolean multiplayer = mc.theWorld != null && !mc.isSingleplayer();
        // The catalogue and your own outfit arrive over this socket, so it is kept up even in a menu
        // (the wardrobe can be opened from there); only player tracking needs a world.
        final boolean wanted = config.enabled && config.termsNoticeSeen && config.essential
                && !com.ootruffle.clientmetics.compat.EssentialCompat.installed();
        final boolean inWorld = mc.theWorld != null && (multiplayer || config.showOwn);
        if (wanted != wasWanted) {
            wasWanted = wanted;
            ticks = 0;
            connectTicks = 0;
            if (!wanted) {
                OUTFITS.clear();
                REQUESTED.clear();
                WORKER.execute(EssentialCosmeticManager::disconnect);
            }
        }
        if (!wanted) {
            // Say why once, so a log without any Essential lines is answerable.
            if (!loggedWhy) {
                loggedWhy = true;
                if (!config.enabled) {
                    Clientmetics.LOGGER.info("Essential cosmetics are off: Clientmetics is disabled");
                } else if (!config.termsNoticeSeen) {
                    Clientmetics.LOGGER.info("Essential cosmetics are waiting for the first-launch prompt to be accepted");
                } else if (!config.essential) {
                    Clientmetics.LOGGER.info("Essential cosmetics are off in the settings");
                } else {
                    Clientmetics.LOGGER.info("Essential is installed; leaving Essential cosmetics to it");
                }
            }
            return;
        }
        loggedWhy = false;
        if (socket == null && ++connectTicks >= CONNECT_RETRY_TICKS) {
            connectTicks = 0;
            WORKER.execute(EssentialCosmeticManager::connectNow);
        }
        if (!inWorld || mc.thePlayer == null || ++ticks < SYNC_INTERVAL_TICKS) {
            return;
        }
        ticks = 0;

        final EntityPlayer self = mc.thePlayer;
        final ArrayList<EntityPlayer> players = new ArrayList<>(mc.theWorld.playerEntities);
        players.sort(Comparator.comparingDouble(p -> p.getDistanceSqToEntity(self)));
        final Set<UUID> visible = new LinkedHashSet<>();
        for (EntityPlayer player : players) {
            if (visible.size() >= Math.max(1, config.maxPlayers)) {
                break;
            }
            final UUID uuid = player.getUniqueID();
            // Always fetch the local outfit for the Essential wardrobe. Rendering it on the player
            // remains controlled by showOwn in CosmeticRenderer.
            if (uuid == null || uuid.version() != 4) {
                continue;
            }
            visible.add(uuid);
        }
        if (PENDING.getAndSet(visible) == null) {
            WORKER.execute(EssentialCosmeticManager::sync);
        }
    }

    private static void disconnect() {
        if (socket != null) {
            socket.close();
            socket = null;
            status = "";
            Clientmetics.LOGGER.info("Disconnected from Essential");
        }
        tracked.clear();
        OUTFITS.clear();
        REQUESTED.clear();
        owned.clear();
        selectedOutfitId = null;
        EssentialAccount.clear();
    }

    private static void sync() {
        final Set<UUID> visible = PENDING.getAndSet(null);
        if (visible == null || !ensureConnected()) {
            return;
        }
        final Set<UUID> gone = new HashSet<>(tracked);
        gone.removeAll(visible);
        final Set<UUID> added = new LinkedHashSet<>(visible);
        added.removeAll(tracked);
        for (UUID uuid : gone) {
            OUTFITS.remove(uuid);
            REQUESTED.remove(uuid);
        }
        tracked.removeAll(gone);
        tracked.addAll(visible);
        // Subscribe first: that's what makes Essential push later outfit changes for these players.
        if (!gone.isEmpty()) {
            socket.updateSubscription(gone, false);
        }
        if (!added.isEmpty()) {
            socket.updateSubscription(added, true);
        }

        final long now = System.currentTimeMillis();
        int asked = 0;
        for (UUID uuid : visible) {
            if (asked >= MAX_REQUESTS_PER_SYNC) {
                break;
            }
            final Long last = REQUESTED.get(uuid);
            if (last != null && now - last < REQUEST_TTL_MS) {
                continue;
            }
            REQUESTED.put(uuid, now);
            socket.requestOutfit(uuid);
            asked++;
        }
    }

    /** Dispatches one packet Essential pushed. Runs on the socket's thread. */
    private static void onPacket(String className, JsonObject payload) {
        try {
            if (className.endsWith("ServerCosmeticsPopulatePacket")) {
                EssentialCatalog.accept(cosmetics(payload));
                // Outfits that arrived before the catalogue couldn't be preloaded; catch up now.
                for (EssentialSnapshot snapshot : OUTFITS.values()) {
                    com.ootruffle.clientmetics.render.CosmeticRenderer.preloadEssential(snapshot);
                }
            } else if (className.endsWith("ServerCosmeticOutfitSelectedResponsePacket")) {
                // Full outfit for one player: long field names.
                store(text(payload, "uuid"), map(payload, "equippedCosmetics"), settings(payload, "cosmeticSettings"));
            } else if (className.endsWith("ServerCosmeticsUserEquippedPacket")) {
                // A player changed what they're wearing: short keys.
                store(text(payload, "a"), map(payload, "b"), null);
            } else if (className.endsWith("ServerCosmeticPlayerSettingsPacket")) {
                updateSettings(text(payload, "a"), settings(payload, "b"));
            } else if (className.endsWith("ServerCosmeticOutfitPopulatePacket")) {
                acceptOutfits(payload);
            } else if (className.endsWith("ServerCosmeticsUserUnlockedPacket")) {
                acceptUnlocked(payload);
            } else if (Config.get().debug) {
                Clientmetics.LOGGER.info("Essential: ignoring {}", className);
            }
        } catch (RuntimeException e) {
            Clientmetics.LOGGER.warn("Essential: couldn't read {}: {}", className, e.toString());
        }
    }

    private static void store(String rawUuid, Map<String, String> equipped, Map<String, JsonArray> settings) {
        if (rawUuid == null || equipped == null) {
            return;
        }
        final UUID uuid = EssentialSocket.parseUuid(rawUuid);
        if (uuid == null) {
            return;
        }
        final EssentialSnapshot previous = OUTFITS.get(uuid);
        final Map<String, JsonArray> merged = settings != null ? settings
                : previous != null ? previous.settings : Collections.<String, JsonArray>emptyMap();
        final EssentialSnapshot snapshot = new EssentialSnapshot(uuid, equipped, merged);
        OUTFITS.put(uuid, snapshot);
        com.ootruffle.clientmetics.render.CosmeticRenderer.preloadEssential(snapshot);
        if (uuid.equals(selfId())) {
            refreshAccountState();
        }
        if (Config.get().debug) {
            Clientmetics.LOGGER.info("Essential outfit for {}: {} settings {}", uuid, equipped, merged);
        }
    }

    private static void updateSettings(String rawUuid, Map<String, JsonArray> settings) {
        if (rawUuid == null || settings == null) {
            return;
        }
        final UUID uuid = EssentialSocket.parseUuid(rawUuid);
        if (uuid == null) {
            return;
        }
        final EssentialSnapshot previous = OUTFITS.get(uuid);
        if (previous == null) {
            return;
        }
        OUTFITS.put(uuid, new EssentialSnapshot(uuid, previous.equipped, settings));
        if (uuid.equals(selfId())) {
            refreshAccountState();
        }
    }

    /**
     * {@code ServerCosmeticOutfitPopulatePacket}: every outfit on the account. The selected one is
     * what the wardrobe edits and what other players see ({@code a} id, {@code d} equipped cosmetics,
     * {@code e} per-cosmetic settings, {@code f} selected).
     */
    private static void acceptOutfits(JsonObject payload) {
        final JsonElement outfits = payload.get("outfits");
        if (outfits == null || !outfits.isJsonArray()) {
            return;
        }
        for (JsonElement element : outfits.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject outfit = element.getAsJsonObject();
            final JsonElement selected = outfit.get("f");
            if (selected == null || !selected.isJsonPrimitive() || !selected.getAsBoolean()) {
                continue;
            }
            selectedOutfitId = text(outfit, "a");
            final UUID self = selfId();
            final Map<String, String> equipped = map(outfit, "d");
            if (self != null && equipped != null) {
                store(self.toString(), equipped, settings(outfit, "e"));
            }
            break;
        }
        refreshAccountState();
    }

    /**
     * {@code ServerCosmeticsUserUnlockedPacket}: cosmetics an account owns ({@code d}).
     *
     * <p>The packet can be about <b>another</b> account ({@code c} carries the target UUID, e.g. a
     * gift or a friend's purchase notification), so anything not addressed to us is ignored —
     * otherwise other people's unlocks would be shown as ours.
     */
    private static void acceptUnlocked(JsonObject payload) {
        final String target = text(payload, "c");
        if (target != null) {
            final UUID self = selfId();
            if (self == null || !self.equals(EssentialSocket.parseUuid(target))) {
                if (Config.get().debug) {
                    Clientmetics.LOGGER.info("Essential: ignoring unlocks for another account ({})", target);
                }
                return;
            }
        }
        final JsonElement unlocked = payload.get("d");
        if (unlocked == null || !unlocked.isJsonObject()) {
            return;
        }
        owned.addAll(unlocked.getAsJsonObject().keySet());
        refreshAccountState();
        Clientmetics.LOGGER.info("Essential: {} cosmetics owned", owned.size());
    }

    /**
     * Rebuilds the wardrobe's view of this account. Any thread.
     *
     * <p>Only cosmetics the account actually owns are ever handed to the wardrobe: the ids Essential
     * reports as unlocked, plus whatever is on the account's own outfit — being equipped means it is
     * owned, and it keeps a partial unlock list from hiding (or refusing to unequip) what is worn.
     * The catalogue is never used as a source of cards, so nothing unowned can appear.
     */
    private static void refreshAccountState() {
        final UUID self = selfId();
        final EssentialSnapshot snapshot = self == null ? null : OUTFITS.get(self);
        final Map<String, String> slots = snapshot == null
                ? Collections.<String, String>emptyMap() : snapshot.equipped;
        final Set<String> visible = new LinkedHashSet<>(owned);
        visible.addAll(slots.values());
        EssentialAccount.publish(selectedOutfitId, slots, visible);
    }

    /** This game's account, or null if it has none. */
    public static UUID selfId() {
        return Minecraft.getMinecraft().getSession().getProfile().getId();
    }

    /** Asks Essential for this account's outfit again. Returns false when there's no connection. */
    public static boolean requestSelf() {
        final EssentialSocket s = socket;
        final UUID self = selfId();
        if (s == null || !s.isOpen() || self == null) {
            return false;
        }
        REQUESTED.remove(self);
        s.requestOutfit(self);
        return true;
    }

    /**
     * Equips (or with a null {@code cosmeticId}, clears) one slot of the selected outfit, and shows
     * the change on this player straight away. Returns false when there's nothing to send it on.
     */
    public static boolean equip(String slot, String cosmeticId) {
        final EssentialSocket s = socket;
        final String outfitId = selectedOutfitId;
        if (s == null || !s.isOpen() || outfitId == null || slot == null) {
            return false;
        }
        s.equipCosmetic(outfitId, slot, cosmeticId);
        applyLocalEquip(slot, cosmeticId);
        return true;
    }

    /** Mirrors an equip we just sent into the local outfit, so it shows before Essential confirms it. */
    private static void applyLocalEquip(String slot, String cosmeticId) {
        final UUID self = selfId();
        if (self == null) {
            return;
        }
        final EssentialSnapshot previous = OUTFITS.get(self);
        if (previous == null) {
            return;
        }
        final Map<String, String> next = new LinkedHashMap<>(previous.equipped);
        if (cosmeticId == null) {
            next.remove(slot);
        } else {
            next.put(slot, cosmeticId);
        }
        store(self.toString(), next, previous.settings);
    }

    /** Worker task: bring the socket up and ask for our own outfit, without touching player tracking. */
    private static void connectNow() {
        if (ensureConnected()) {
            requestSelf();
        }
    }

    private static boolean ensureConnected() {
        if (socket != null && socket.isOpen()) {
            return true;
        }
        final long now = System.currentTimeMillis();
        if (socket != null) {
            Clientmetics.LOGGER.info("Essential socket closed");
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
        final UUID uuid = mc.getSession().getProfile().getId();
        final String name = mc.getSession().getUsername();
        if (uuid == null || mc.getSession().getToken() == null || mc.getSession().getToken().length() < 32) {
            if (!warnedOffline) {
                warnedOffline = true;
                Clientmetics.LOGGER.warn("Not connecting to Essential: this account isn't logged in to Microsoft (offline or dev session)");
            }
            status = "Essential needs a Microsoft-signed-in account (this is an offline or dev session).";
            nextConnectAttempt = now + MAX_BACKOFF_MS;
            return false;
        }
        try {
            socket = EssentialSocket.connect(uuid, name, EssentialCosmeticManager::onPacket);
            connectedAt = System.currentTimeMillis();
            tracked.clear();
            status = "";
            Clientmetics.LOGGER.info("Connected to Essential");
            return true;
        } catch (EssentialSocket.OutdatedException e) {
            // Retrying can't help: Essential refuses this client version until it's updated.
            status = "Essential refused this client version; it may need updating.";
            Clientmetics.LOGGER.warn("Essential refused this client version ({}); not retrying", e.getMessage());
            nextConnectAttempt = Long.MAX_VALUE;
            return false;
        } catch (Exception e) {
            status = "Couldn't connect to Essential: " + e;
            Clientmetics.LOGGER.warn("Couldn't connect to Essential ({}), retrying in {}s", e.toString(), backoff / 1000);
            scheduleRetry(now);
            return false;
        }
    }

    private static void scheduleRetry(long now) {
        nextConnectAttempt = now + backoff;
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
    }

    // ---- payload helpers ----

    private static JsonArray cosmetics(JsonObject payload) {
        final JsonElement e = payload.get("a");
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String text(JsonObject payload, String key) {
        final JsonElement e = payload.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    /** {@code {"HAT": "COSMETIC_ID", ...}}, skipping anything that isn't a string. */
    private static Map<String, String> map(JsonObject payload, String key) {
        final JsonElement e = payload.get(key);
        if (e == null || !e.isJsonObject()) {
            return null;
        }
        final Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> slot : e.getAsJsonObject().entrySet()) {
            final JsonElement value = slot.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && !value.getAsString().isEmpty()) {
                out.put(slot.getKey(), value.getAsString());
            }
        }
        return out;
    }

    /** {@code {"COSMETIC_ID": [setting, ...]}}. */
    private static Map<String, JsonArray> settings(JsonObject payload, String key) {
        final JsonElement e = payload.get(key);
        if (e == null || !e.isJsonObject()) {
            return null;
        }
        final Map<String, JsonArray> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
            if (entry.getValue().isJsonArray()) {
                out.put(entry.getKey(), entry.getValue().getAsJsonArray());
            }
        }
        return out;
    }

}
