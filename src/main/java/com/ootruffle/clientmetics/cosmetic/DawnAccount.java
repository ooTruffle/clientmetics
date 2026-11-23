package com.ootruffle.clientmetics.cosmetic;

import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.asset.DawnCatalog;
import com.ootruffle.clientmetics.net.DawnApi;
import com.ootruffle.clientmetics.net.DawnAuthenticator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;

/**
 * Your own Dawn account, for the wardrobe: which cosmetics you own and what's in each slot.
 * Equipping or unequipping saves the whole slot map with {@code PUT /cosmetics/equipped}, the way
 * the Dawn client does, so Dawn users (and Clientmetics users) see the change.
 * <p>
 * Dawn has one cosmetic per slot ({@code COSMETIC_TYPE_HAT}, {@code COSMETIC_TYPE_CAPE}...), so
 * equipping replaces whatever is in that slot. Slots this mod doesn't know are sent back unchanged.
 */
public final class DawnAccount {

    /** Catalogue types Dawn lets you put in a slot (emotes and trims are equipped elsewhere). */
    public static final Set<String> EQUIPPABLE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "cape", "hat", "boot", "wing", "aura", "pet", "face", "back", "arm", "shield")));
    private static final String SLOT_PREFIX = "COSMETIC_TYPE_";

    /** An immutable snapshot of the account, swapped whole so the screen always reads a consistent one. */
    public static final class State {
        /** Owned slugs. */
        public final List<String> owned;
        /** Slot ({@code COSMETIC_TYPE_HAT}) to slug, as saved on Dawn. */
        public final Map<String, String> slots;

        State(List<String> owned, Map<String, String> slots) {
            this.owned = Collections.unmodifiableList(new ArrayList<>(owned));
            this.slots = Collections.unmodifiableMap(new LinkedHashMap<>(slots));
        }

        public boolean isEquipped(String slug) {
            return slots.containsValue(slug);
        }
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Clientmetics-DawnAccount");
        t.setDaemon(true);
        return t;
    });

    private static volatile State state;
    private static volatile String status = "";
    private static volatile boolean busy;
    /** Worker thread only: the access token, reused until Dawn refuses it. */
    private static String jwt;
    /** Dev-only sample data (see DevAutotest): changes stay local and nothing is sent to Dawn. */
    private static volatile boolean demo;

    private DawnAccount() {}

    /** Dev only: fills the wardrobe with sample cosmetics so the screen can be checked without a Dawn account. */
    public static void loadDemo(List<String> ownedSlugs) {
        demo = true;
        state = new State(ownedSlugs, new LinkedHashMap<>());
        status = "Demo data (dev only, nothing is sent to Dawn)";
    }

    /** The loaded account, or null before {@link #refresh()} has finished. */
    public static State state() {
        return state;
    }

    public static String status() {
        return status;
    }

    public static boolean isBusy() {
        return busy;
    }

    /** {@code COSMETIC_TYPE_HAT} for {@code hat}. */
    public static String slotFor(String type) {
        return SLOT_PREFIX + type.toUpperCase(Locale.ROOT);
    }

    /** Loads owned cosmetics and the equipped slots from Dawn. Signs in first if needed. */
    public static void refresh() {
        if (demo || busy) {
            return;
        }
        final String problem = unavailable();
        if (problem != null) {
            status = problem;
            return;
        }
        busy = true;
        status = "Loading your Dawn cosmetics...";
        WORKER.execute(() -> {
            try {
                final List<String> owned = withToken(DawnApi::owned);
                final Map<String, String> slots = withToken(DawnApi::equipped);
                state = new State(owned, slots);
                showSelf(state);
                status = owned.isEmpty() ? "Your Dawn account doesn't own any cosmetics yet." : "";
            } catch (Exception e) {
                status = "Couldn't load your Dawn cosmetics: " + message(e);
                Clientmetics.LOGGER.warn("Couldn't load the Dawn wardrobe: {}", e.toString());
            } finally {
                busy = false;
            }
        });
    }

    /** Equips an owned cosmetic (replacing whatever is in its slot) or unequips it, and saves it on Dawn. */
    public static void toggle(String slug) {
        final State current = state;
        if (current == null || busy) {
            return;
        }
        if (!current.owned.contains(slug)) {
            status = "Your Dawn account doesn't own that cosmetic.";
            return;
        }
        final DawnCatalog catalog = DawnCatalog.get();
        final DawnCatalog.Entry entry = catalog == null ? null : catalog.entry(slug);
        final boolean unequip = current.isEquipped(slug);
        if (!unequip && (entry == null || !EQUIPPABLE.contains(entry.type))) {
            status = "That kind of cosmetic can't be equipped from here.";
            return;
        }
        final Map<String, String> next = new LinkedHashMap<>(current.slots);
        if (unequip) {
            next.values().removeIf(slug::equals);
        } else {
            next.put(slotFor(entry.type), slug); // One per slot: replaces the old one.
        }
        final String name = entry == null ? slug : entry.name;
        if (demo) {
            state = new State(current.owned, next);
            showSelf(state);
            return;
        }
        final String problem = unavailable();
        if (problem != null) {
            status = problem;
            return;
        }
        busy = true;
        status = (unequip ? "Unequipping " : "Equipping ") + name + "...";
        WORKER.execute(() -> {
            try {
                final Map<String, String> saved = withToken(token -> DawnApi.setEquipped(token, next));
                state = new State(current.owned, saved);
                showSelf(state);
                status = "Saved to your Dawn account.";
            } catch (Exception e) {
                status = "Dawn didn't save it: " + message(e);
                Clientmetics.LOGGER.warn("Couldn't save the Dawn outfit: {}", e.toString());
            } finally {
                busy = false;
            }
        });
    }

    /** Why the account can't be reached right now, or null if it can. */
    private static String unavailable() {
        final Config config = Config.get();
        if (!config.dawn) {
            return "Dawn cosmetics are turned off in the settings.";
        }
        if (DawnCatalog.get() == null) {
            return "Dawn's catalogue isn't loaded yet; try again in a moment.";
        }
        final net.minecraft.util.Session session = Minecraft.getMinecraft().getSession();
        if (session.getProfile().getId() == null || session.getToken() == null || session.getToken().length() < 32) {
            return "Dawn needs a Microsoft-signed-in account (this is an offline or dev session).";
        }
        return null;
    }

    private interface Call<T> {
        T run(String token) throws Exception;
    }

    /** Runs a call with the current token, signing in again once if Dawn refuses it. Worker thread. */
    private static <T> T withToken(Call<T> call) throws Exception {
        if (jwt == null) {
            jwt = DawnCosmeticManager.token();
        }
        if (jwt == null) {
            jwt = DawnAuthenticator.fetchToken();
        }
        try {
            return call.run(jwt);
        } catch (DawnApi.Unauthorized e) {
            jwt = DawnAuthenticator.fetchToken();
            return call.run(jwt);
        }
    }

    /** Shows the saved outfit on your own player straight away (the gateway confirms it later). */
    private static void showSelf(State s) {
        final UUID self = Minecraft.getMinecraft().getSession().getProfile().getId();
        if (self == null) {
            return;
        }
        final Map<String, String> outfit = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : s.slots.entrySet()) {
            final String key = e.getKey().startsWith(SLOT_PREFIX) ? e.getKey().substring(SLOT_PREFIX.length()) : e.getKey();
            outfit.put(key.toLowerCase(Locale.ROOT), e.getValue());
        }
        DawnCosmeticManager.showOutfit(DawnCosmeticSnapshot.of(self, outfit));
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

}
