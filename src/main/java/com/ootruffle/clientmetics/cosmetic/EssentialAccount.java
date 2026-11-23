package com.ootruffle.clientmetics.cosmetic;

import com.ootruffle.clientmetics.asset.EssentialCatalog;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Your own Essential account, for the wardrobe: the outfit Essential has selected and the cosmetics
 * it reports as owned.
 *
 * <p>Equipping sends {@code ClientCosmeticOutfitEquippedCosmeticsUpdatePacket} (outfit id, slot,
 * cosmetic id or null) — the same call Essential's own wardrobe makes — so the change is saved on
 * your Essential account and other Essential users see it. It never grants anything: the backend
 * decides what you own and rejects the rest.
 *
 * <p>Essential allows one cosmetic per slot, so equipping replaces whatever was in that slot.
 * Changes are applied locally first and confirmed by whatever Essential echoes back.
 */
public final class EssentialAccount {

    /**
     * Slots the wardrobe can put something in. Emotes and icons aren't worn and effects are
     * particles; capes are kept because owned cape cosmetics are real cosmetics — only the raw cape
     * texture hash Essential uses for a player's own Minecraft cape is filtered out.
     */
    public static final Set<String> EQUIPPABLE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "HAT", "HEAD", "FACE", "EARS", "CAPE", "BACK", "WINGS", "TAIL", "PET", "ARMS", "SHOULDERS",
            "SUITS", "TOP", "SKIRT", "PANTS", "SHOES", "FULL_BODY", "ACCESSORY")));

    /** An immutable snapshot, swapped whole so the screen always reads a consistent one. */
    public static final class State {
        /** The id of the outfit Essential currently has selected. */
        public final String outfitId;
        /** Slot ({@code HAT}) to cosmetic id. */
        public final Map<String, String> slots;
        /** Cosmetic ids Essential says the account owns. */
        public final Set<String> owned;

        State(String outfitId, Map<String, String> slots, Set<String> owned) {
            this.outfitId = outfitId;
            this.slots = Collections.unmodifiableMap(new LinkedHashMap<>(slots));
            this.owned = Collections.unmodifiableSet(new LinkedHashSet<>(owned));
        }

        public boolean isEquipped(String cosmeticId) {
            return cosmeticId != null && slots.containsValue(cosmeticId);
        }

        /** The slot holding {@code cosmeticId}, or null. */
        public String slotOf(String cosmeticId) {
            for (Map.Entry<String, String> slot : slots.entrySet()) {
                if (slot.getValue().equals(cosmeticId)) {
                    return slot.getKey();
                }
            }
            return null;
        }
    }

    private static volatile State state;
    /** A one-off note (e.g. "Saved"), cleared when Essential sends something newer. */
    private static volatile String message = "";
    private static volatile boolean busy;
    /** Dev-only sample data (see DevAutotest): changes stay local and nothing is sent to Essential. */
    private static volatile boolean demo;

    private EssentialAccount() {}

    /** Dev only: fills the wardrobe with sample data so it can be checked without an Essential account. */
    public static void loadDemo(java.util.Collection<String> ownedIds, Map<String, String> slots) {
        demo = true;
        state = new State("demo", slots, new LinkedHashSet<>(ownedIds));
        message = "Demo data (dev only, nothing is sent to Essential)";
    }

    /** The outfit the wardrobe shows, or null until Essential has sent one. */
    public static State state() {
        return state;
    }

    public static boolean isBusy() {
        return busy;
    }

    public static String status() {
        if (message != null && !message.isEmpty()) {
            return message;
        }
        if (!EssentialCosmeticManager.isConnected()) {
            final String reason = EssentialCosmeticManager.status();
            return reason == null || reason.isEmpty() ? "Not connected to Essential." : reason;
        }
        if (EssentialCatalog.get() == null) {
            return "Waiting for Essential's cosmetic catalogue...";
        }
        if (state == null) {
            return "Waiting for Essential to send your outfit...";
        }
        return state.owned.isEmpty() ? "Essential hasn't reported which cosmetics you own yet." : "";
    }

    /** Called by the manager whenever an outfit, the selected outfit, or the owned set changes. */
    static void publish(String outfitId, Map<String, String> slots, Set<String> owned) {
        if (demo) {
            return; // Dev sample data stays put.
        }
        message = "";
        state = outfitId == null ? null : new State(outfitId, slots, owned);
    }

    /** Forgets everything (disconnected, or Essential support turned off). */
    static void clear() {
        message = "";
        state = null;
        busy = false;
    }

    /** Asks Essential for the current outfit again; the answer arrives asynchronously. */
    public static void refresh() {
        message = EssentialCosmeticManager.requestSelf() ? "Refreshing from Essential..." : "Not connected to Essential.";
    }

    /** Equips an owned cosmetic in its slot (replacing what was there), or unequips it. */
    public static void toggle(String cosmeticId) {
        final State current = state;
        if (cosmeticId == null || busy) {
            return;
        }
        if (current == null) {
            message = "Essential hasn't sent your outfit yet.";
            return;
        }
        final EssentialCatalog catalog = EssentialCatalog.get();
        final EssentialCatalog.Entry entry = catalog == null ? null : catalog.entry(cosmeticId);
        if (entry == null) {
            message = "Essential's catalogue doesn't have that cosmetic.";
            return;
        }
        if (!current.owned.contains(cosmeticId)) {
            message = "Your Essential account doesn't own that cosmetic.";
            return;
        }
        final boolean unequip = current.isEquipped(cosmeticId);
        final String slot = unequip ? current.slotOf(cosmeticId) : entry.type;
        if (slot == null || !EQUIPPABLE.contains(slot)) {
            message = "That kind of cosmetic can't be equipped from here.";
            return;
        }
        if (demo) {
            final Map<String, String> next = new LinkedHashMap<>(current.slots);
            if (unequip) {
                next.remove(slot);
            } else {
                next.put(slot, cosmeticId);
            }
            state = new State(current.outfitId, next, current.owned);
            final java.util.UUID self = EssentialCosmeticManager.selfId();
            final EssentialSnapshot live = EssentialCosmeticManager.outfit(self);
            if (self != null) {
                EssentialCosmeticManager.showOutfit(new EssentialSnapshot(self, next,
                        live == null ? Collections.<String, com.google.gson.JsonArray>emptyMap() : live.settings));
            }
            message = (unequip ? "Unequipped " : "Equipped ") + entry.name + " (demo).";
            return;
        }
        if (!EssentialCosmeticManager.isConnected()) {
            message = "Not connected to Essential.";
            return;
        }
        busy = true;
        try {
            if (!EssentialCosmeticManager.equip(slot, unequip ? null : cosmeticId)) {
                message = "Couldn't send that change to Essential.";
                return;
            }
            message = (unequip ? "Unequipped " : "Equipped ") + entry.name + ".";
        } finally {
            busy = false;
        }
    }

}
