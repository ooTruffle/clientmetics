package com.ootruffle.clientmetics.cosmetic;

import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.asset.Catalog;
import com.ootruffle.clientmetics.net.LunarSocket;
import com.ootruffle.clientmetics.net.ProtoMessage;
import com.ootruffle.clientmetics.net.ProtoWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/**
 * Your own Lunar account, for the wardrobe: which cosmetics you own and what your default outfit
 * wears. Equipping or unequipping sends {@code UpdateOutfit} for that outfit, so the change is
 * saved on Lunar and every Lunar user (and Clientmetics user) sees it.
 * <p>
 * Only cosmetics Lunar lists as owned can be equipped. The outfit is edited as raw protobuf: only
 * its cosmetics list (field 3) is replaced, everything else is sent back unchanged.
 */
public final class LunarAccount {

    /** An immutable snapshot of the account, swapped whole so the screen always reads a consistent one. */
    public static final class State {
        public final List<Integer> owned;
        public final List<EquippedCosmetic> equipped;
        final List<byte[]> equippedRaw;
        final byte[] outfit;

        State(List<Integer> owned, byte[] outfit, List<byte[]> equippedRaw) {
            this.owned = Collections.unmodifiableList(owned);
            this.outfit = outfit;
            this.equippedRaw = Collections.unmodifiableList(equippedRaw);
            final List<EquippedCosmetic> decoded = new ArrayList<>();
            for (byte[] raw : equippedRaw) {
                decoded.add(EquippedCosmetic.decode(ProtoMessage.parse(raw)));
            }
            this.equipped = Collections.unmodifiableList(decoded);
        }

        public boolean isEquipped(int id) {
            for (EquippedCosmetic c : equipped) {
                if (c.id == id) {
                    return true;
                }
            }
            return false;
        }
    }

    private static volatile State state;
    private static volatile String status = "";
    private static volatile boolean busy;
    /** Dev-only sample data (see DevAutotest): changes stay local and nothing is sent to Lunar. */
    private static volatile boolean demo;

    /** Dev only: fills the wardrobe with sample cosmetics so the screen can be checked without a Lunar account. */
    public static void loadDemo(List<Integer> ownedIds) {
        demo = true;
        state = new State(new ArrayList<>(ownedIds), new ProtoWriter().string(2, "Demo").toByteArray(), new ArrayList<>());
        status = "Demo data (dev only, nothing is sent to Lunar)";
    }

    private LunarAccount() {}

    /** The loaded account, or null before {@link #refresh()} has finished. */
    public static State state() {
        return state;
    }

    /** A short line for the screen: what's happening or what went wrong. */
    public static String status() {
        return status;
    }

    public static boolean isBusy() {
        return busy;
    }

    /** Loads owned cosmetics and the default outfit from Lunar ({@code CosmeticService.Login}). */
    public static void refresh() {
        if (demo) {
            return;
        }
        final LunarSocket socket = CosmeticManager.socket();
        if (socket == null) {
            status = "Not connected to Lunar yet. Join a server (or a world with \"Show My Own Cosmetics\" on) and try again.";
            return;
        }
        if (busy) {
            return;
        }
        busy = true;
        status = "Loading your Lunar cosmetics...";
        socket.login().whenComplete((res, error) -> {
            busy = false;
            if (error != null) {
                status = "Couldn't load your cosmetics: " + error.getMessage();
                Clientmetics.LOGGER.warn("CosmeticService.Login failed: {}", error.toString());
                return;
            }
            try {
                state = parseLogin(res);
                final UUID self = Minecraft.getMinecraft().getSession().getProfile().getId();
                if (self != null) {
                    CosmeticManager.showOutfit(self, state.equipped); // So the preview shows it straight away.
                }
                status = state.outfit == null
                        ? "You have no Lunar outfit yet. Create one in Lunar Client once, then come back."
                        : "";
            } catch (RuntimeException e) {
                status = "Couldn't read Lunar's reply: " + e;
                Clientmetics.LOGGER.warn("Couldn't parse CosmeticService.Login", e);
            }
        });
    }

    /** Equips an owned cosmetic (replacing another of the same type) or unequips it, and saves the outfit on Lunar. */
    public static void toggle(int cosmeticId) {
        final State current = state;
        final LunarSocket socket = CosmeticManager.socket();
        if (current == null || current.outfit == null || busy) {
            return;
        }
        if (socket == null && !demo) {
            status = "Lost the connection to Lunar; it reconnects by itself, then try again.";
            return;
        }
        if (!current.owned.contains(cosmeticId)) {
            status = "Your Lunar account doesn't own that cosmetic.";
            return;
        }
        final Catalog catalog = Catalog.get();
        final Catalog.Entry entry = catalog == null ? null : catalog.entry(cosmeticId);
        final String category = entry == null ? null : entry.category;

        final List<byte[]> next = new ArrayList<>();
        final boolean unequip = current.isEquipped(cosmeticId);
        for (int i = 0; i < current.equipped.size(); i++) {
            final EquippedCosmetic c = current.equipped.get(i);
            if (c.id == cosmeticId) {
                continue;
            }
            final Catalog.Entry other = catalog == null ? null : catalog.entry(c.id);
            if (!unequip && category != null && other != null && category.equals(other.category)) {
                continue; // One per type: the new one replaces it.
            }
            next.add(current.equippedRaw.get(i));
        }
        if (!unequip) {
            next.add(new ProtoWriter().varint(1, cosmeticId).toByteArray());
        }

        save(current, socket, next, (unequip ? "Unequipping " : "Equipping ") + (entry == null ? "#" + cosmeticId : entry.name) + "...");
    }

    /**
     * Turns one of an equipped cosmetic's settings on or off and saves the outfit: {@code field}
     * is an {@code EquippedCosmetic} bool (3 cloth_cloak, 4 show_over_skin_layer, 5 helmet,
     * 6 chestplate, 7 leggings, 8 boots, 10 flip_arm).
     */
    public static void setFlag(int cosmeticId, int field, boolean value) {
        editEquipped(cosmeticId, raw -> {
            final ProtoWriter w = new ProtoWriter().raw(ProtoMessage.without(raw, field));
            if (value) {
                w.varint(field, 1); // Proto3 leaves false out.
            }
            return w.toByteArray();
        });
    }

    /** Sets an equipped hat's height offset (field 9) and saves the outfit. */
    public static void setHeightOffset(int cosmeticId, float offset) {
        editEquipped(cosmeticId, raw -> {
            final ProtoWriter w = new ProtoWriter().raw(ProtoMessage.without(raw, 9));
            if (offset != 0) {
                w.float32(9, offset);
            }
            return w.toByteArray();
        });
    }

    private static void editEquipped(int cosmeticId, java.util.function.UnaryOperator<byte[]> edit) {
        final State current = state;
        final LunarSocket socket = CosmeticManager.socket();
        if (current == null || current.outfit == null || busy || !current.isEquipped(cosmeticId)) {
            return;
        }
        if (socket == null && !demo) {
            status = "Lost the connection to Lunar; it reconnects by itself, then try again.";
            return;
        }
        final List<byte[]> next = new ArrayList<>();
        for (int i = 0; i < current.equipped.size(); i++) {
            final byte[] raw = current.equippedRaw.get(i);
            next.add(current.equipped.get(i).id == cosmeticId ? edit.apply(raw) : raw);
        }
        save(current, socket, next, "Saving...");
    }

    /** Sends the outfit with its cosmetics list replaced by {@code next} (only field 3 changes). */
    private static void save(State current, LunarSocket socket, List<byte[]> next, String working) {
        final ProtoWriter outfit = new ProtoWriter().raw(ProtoMessage.without(current.outfit, 3));
        for (byte[] raw : next) {
            outfit.bytes(3, raw);
        }
        final byte[] sent = outfit.toByteArray();
        if (demo) {
            state = new State(current.owned, sent, next);
            final UUID self = Minecraft.getMinecraft().getSession().getProfile().getId();
            if (self != null) {
                CosmeticManager.showOutfit(self, state.equipped);
            }
            return;
        }
        busy = true;
        status = working;
        socket.updateOutfit(sent).whenComplete((res, error) -> {
            busy = false;
            if (error != null) {
                status = "Lunar didn't save it: " + error.getMessage();
                Clientmetics.LOGGER.warn("UpdateOutfit failed: {}", error.toString());
                return;
            }
            if (res.varint(1, 0) != 1) {
                status = "Lunar refused the change (status " + res.varint(1, 0) + ").";
                return;
            }
            final byte[] saved = res.bytes(2) != null ? res.bytes(2) : sent;
            final State updated = new State(current.owned, saved, ProtoMessage.parse(saved).bytesList(3));
            state = updated;
            status = "Saved to your Lunar account.";
            final UUID self = Minecraft.getMinecraft().getSession().getProfile().getId();
            if (self != null) {
                CosmeticManager.showOutfit(self, updated.equipped);
            }
        });
    }

    private static State parseLogin(ProtoMessage res) {
        final long now = System.currentTimeMillis() / 1000;
        final Set<Integer> owned = new LinkedHashSet<>();
        for (ProtoMessage o : res.messages(6)) {
            final ProtoMessage expires = o.message(3);
            if (expires != null && expires.varint(1, Long.MAX_VALUE) < now) {
                continue; // Expired (e.g. a timed giveaway).
            }
            owned.add((int) o.varint(1, 0));
        }
        if (res.bool(7) && Catalog.get() != null) {
            for (Catalog.Entry e : Catalog.get().entries()) {
                owned.add(e.id); // has_all_cosmetics_flag
            }
        }

        // The default outfit is the one the outfit tree names; fall back to the first.
        final ProtoMessage tree = res.message(11);
        final ProtoMessage defaultId = tree == null ? null : tree.message(2);
        byte[] outfit = null;
        for (byte[] raw : res.bytesList(10)) {
            final ProtoMessage o = ProtoMessage.parse(raw);
            final ProtoMessage id = o.message(1);
            if (outfit == null || defaultId != null && id != null && Objects.equals(id.toUuid(), defaultId.toUuid())) {
                outfit = raw;
            }
        }
        return new State(new ArrayList<>(owned), outfit,
                outfit == null ? new ArrayList<>() : ProtoMessage.parse(outfit).bytesList(3));
    }

}
