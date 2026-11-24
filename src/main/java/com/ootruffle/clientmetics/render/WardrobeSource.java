package com.ootruffle.clientmetics.render;

import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.asset.Catalog;
import com.ootruffle.clientmetics.asset.DawnCatalog;
import com.ootruffle.clientmetics.asset.EssentialCatalog;
import com.ootruffle.clientmetics.asset.EssentialFiles;
import com.ootruffle.clientmetics.cosmetic.DawnAccount;
import com.ootruffle.clientmetics.cosmetic.EquippedCosmetic;
import com.ootruffle.clientmetics.cosmetic.EssentialAccount;
import com.ootruffle.clientmetics.cosmetic.EssentialCosmeticManager;
import com.ootruffle.clientmetics.cosmetic.EssentialSnapshot;
import com.ootruffle.clientmetics.cosmetic.LunarAccount;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.client.Minecraft;

/**
 * Whose cosmetics the wardrobe shows: your Lunar account ({@link LunarAccount}) or your Dawn
 * account ({@link DawnAccount}). Both screens draw the same grid from {@link Item}s and leave
 * loading, equipping and previews to the source. Render thread only.
 */
public abstract class WardrobeSource {

    /** One owned cosmetic, as a card. */
    public static final class Item {
        /** Unique across sources, for keys and thumbnails. */
        public final String key;
        public final String name;
        /** Lunar category or Dawn type. */
        public final String category;
        public final boolean equipped;
        /** False when this mod can't draw it (Dawn's AVIF-only textures); it can still be equipped. */
        public final boolean drawable;
        /** The Lunar cosmetic ID, or -1 for Dawn. */
        public final int lunarId;
        /** The Dawn slug, or null for Lunar. */
        public final String dawnSlug;
        /** The Essential id, or null for Lunar and Dawn. */
        public final String essentialId;

        /** One of this mod's own cosmetics (the bell collar): no card, only its settings. */
        public static Item extra(String key, String name) {
            return new Item(key, name, "", true, true, -1, null, null);
        }

        Item(String key, String name, String category, boolean equipped, boolean drawable, int lunarId, String dawnSlug) {
            this(key, name, category, equipped, drawable, lunarId, dawnSlug, null);
        }

        Item(String key, String name, String category, boolean equipped, boolean drawable, int lunarId, String dawnSlug, String essentialId) {
            this.key = key;
            this.name = name;
            this.category = category == null ? "" : category;
            this.equipped = equipped;
            this.drawable = drawable;
            this.lunarId = lunarId;
            this.dawnSlug = dawnSlug;
            this.essentialId = essentialId;
        }
    }

    public static final WardrobeSource LUNAR = new Lunar();
    public static final WardrobeSource DAWN = new Dawn();
    public static final WardrobeSource ESSENTIAL = new Essential();

    private static WardrobeSource current = LUNAR;

    /**
     * The source the wardrobe shows (kept while the game runs). If it was turned off, the first one
     * still on (Lunar if none are, so the screen has something to say why it's empty).
     */
    public static WardrobeSource current() {
        if (!current.available()) {
            final List<WardrobeSource> on = all();
            current = on.isEmpty() ? LUNAR : on.get(0);
        }
        return current;
    }

    public static void select(WardrobeSource source) {
        current = source;
        source.ensureLoaded();
    }

    /** The sources to offer as tabs. */
    public static List<WardrobeSource> all() {
        final List<WardrobeSource> out = new ArrayList<>();
        if (LUNAR.available()) {
            out.add(LUNAR);
        }
        if (DAWN.available()) {
            out.add(DAWN);
        }
        if (ESSENTIAL.available()) {
            out.add(ESSENTIAL);
        }
        return out;
    }

    // Cache: the item lists are rebuilt only when the account state object changes.
    private Object cachedFor;
    private List<Item> cachedOwned = Collections.emptyList();
    private List<Item> cachedEquipped = Collections.emptyList();

    /** "Lunar" or "Dawn". */
    public abstract String name();

    public abstract boolean available();

    /** The account's state object, or null until it's loaded. */
    protected abstract Object state();

    public boolean loaded() {
        return state() != null;
    }

    public abstract String status();

    public abstract boolean busy();

    public abstract void refresh();

    public void ensureLoaded() {
        if (!loaded()) {
            refresh();
        }
    }

    public abstract int ownedCount();

    /** Owned cosmetics this install knows, in no particular order. */
    public List<Item> owned() {
        rebuild();
        return cachedOwned;
    }

    /** What's equipped, in the account's order. */
    public List<Item> equipped() {
        rebuild();
        return cachedEquipped;
    }

    public abstract void toggle(Item item);

    /** Whether this source can change its outfit from the wardrobe. */
    public boolean canToggle() { return true; }

    /** Whether equipped items have settings (Lunar's per-item flags and hat height). */
    public abstract boolean hasSettings();

    /** Draws {@code entity} wearing this account's whole outfit. */
    public abstract void drawOutfit(EntityLivingBase entity, int x, int y, int w, int h, float yaw);

    /** Draws {@code entity} wearing only {@code item}, framed for its type. */
    public abstract void drawItem(EntityLivingBase entity, Item item, int x, int y, int w, int h);

    protected abstract void build(Object state, List<Item> owned, List<Item> equipped);

    private void rebuild() {
        final Object state = state();
        if (state == cachedFor) {
            return;
        }
        cachedFor = state;
        final List<Item> owned = new ArrayList<>();
        final List<Item> equipped = new ArrayList<>();
        if (state != null) {
            build(state, owned, equipped);
        }
        cachedOwned = Collections.unmodifiableList(owned);
        cachedEquipped = Collections.unmodifiableList(equipped);
    }

    /** A tab or card label for a category or type. */
    public static String pretty(String category) {
        if (category == null || category.isEmpty()) {
            return "Other";
        }
        switch (category) {
            case "dragon_wings":
            case "wing":
                return "Wings";
            case "boot":
                return "Boots";
            case "cape":
                return "Cloak";
            default:
                final String s = category.replace('_', ' ');
                return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }

    // ---- Lunar ----

    private static final class Lunar extends WardrobeSource {
        @Override public String name() { return "Lunar"; }
        @Override public boolean available() { return Config.get().lunarOn(); }
        @Override protected Object state() { return Catalog.get() == null ? null : LunarAccount.state(); }
        @Override public String status() { return LunarAccount.status(); }
        @Override public boolean busy() { return LunarAccount.isBusy(); }
        @Override public void refresh() { LunarAccount.refresh(); }
        @Override public boolean hasSettings() { return true; }

        @Override
        public int ownedCount() {
            final LunarAccount.State s = LunarAccount.state();
            return s == null ? 0 : s.owned.size();
        }

        @Override
        public void toggle(Item item) {
            if (item.lunarId >= 0) {
                LunarAccount.toggle(item.lunarId);
            }
        }

        @Override
        protected void build(Object raw, List<Item> owned, List<Item> equipped) {
            final LunarAccount.State state = (LunarAccount.State) raw;
            final Catalog catalog = Catalog.get();
            for (int id : state.owned) {
                final Catalog.Entry e = catalog.entry(id);
                if (e != null) {
                    owned.add(new Item("lunar:" + id, e.name, e.category, state.isEquipped(id), true, id, null));
                }
            }
            for (EquippedCosmetic c : state.equipped) {
                final Catalog.Entry e = catalog.entry(c.id);
                equipped.add(new Item("lunar:" + c.id, e == null ? "#" + c.id : e.name, e == null ? "" : e.category, true, e != null, c.id, null));
            }
        }

        @Override
        public void drawOutfit(EntityLivingBase entity, int x, int y, int w, int h, float yaw) {
            final LunarAccount.State state = LunarAccount.state();
            PreviewRenderer.draw(entity, state == null ? null : state.equipped, PreviewRenderer.FULL, x, y, w, h, yaw);
        }

        @Override
        public void drawItem(EntityLivingBase entity, Item item, int x, int y, int w, int h) {
            final Catalog catalog = Catalog.get();
            PreviewRenderer.draw(entity, Collections.singletonList(EquippedCosmetic.preview(item.lunarId)),
                    PreviewRenderer.framingFor(catalog == null ? null : catalog.entry(item.lunarId)), x, y, w, h, 0);
        }
    }

    // ---- Dawn ----

    private static final class Dawn extends WardrobeSource {
        @Override public String name() { return "Dawn"; }
        @Override public boolean available() { return Config.get().dawnOn() && DawnCatalog.get() != null; }
        @Override protected Object state() { return DawnCatalog.get() == null ? null : DawnAccount.state(); }
        @Override public String status() { return DawnAccount.status(); }
        @Override public boolean busy() { return DawnAccount.isBusy(); }
        @Override public void refresh() { DawnAccount.refresh(); }
        @Override public boolean hasSettings() { return false; }

        @Override
        public int ownedCount() {
            final DawnAccount.State s = DawnAccount.state();
            return s == null ? 0 : s.owned.size();
        }

        @Override
        public void toggle(Item item) {
            if (item.dawnSlug != null) {
                DawnAccount.toggle(item.dawnSlug);
            }
        }

        @Override
        protected void build(Object raw, List<Item> owned, List<Item> equipped) {
            final DawnAccount.State state = (DawnAccount.State) raw;
            final DawnCatalog catalog = DawnCatalog.get();
            for (String slug : state.owned) {
                final DawnCatalog.Entry e = catalog.entry(slug);
                if (e != null && DawnAccount.EQUIPPABLE.contains(e.type)) {
                    owned.add(item(e, state.isEquipped(slug)));
                }
            }
            for (Map.Entry<String, String> slot : state.slots.entrySet()) {
                final DawnCatalog.Entry e = catalog.entry(slot.getValue());
                equipped.add(e != null ? item(e, true) : new Item("dawn:" + slot.getValue(), slot.getValue(), "", true, false, -1, slot.getValue()));
            }
        }

        private static Item item(DawnCatalog.Entry e, boolean equipped) {
            final boolean drawable = e.hasReadableTexture() && ("cape".equals(e.type) || e.geo != null);
            return new Item("dawn:" + e.slug, e.name, e.type, equipped, drawable, -1, e.slug);
        }

        @Override
        public void drawOutfit(EntityLivingBase entity, int x, int y, int w, int h, float yaw) {
            final DawnAccount.State state = DawnAccount.state();
            if (state == null) {
                PreviewRenderer.draw(entity, null, PreviewRenderer.FULL, x, y, w, h, yaw);
            } else {
                PreviewRenderer.drawDawn(entity, state.slots.values(), PreviewRenderer.FULL, x, y, w, h, yaw);
            }
        }

        @Override
        public void drawItem(EntityLivingBase entity, Item item, int x, int y, int w, int h) {
            PreviewRenderer.drawDawn(entity, Collections.singletonList(item.dawnSlug), PreviewRenderer.framingForDawn(item.category),
                    x, y, w, h, 0);
        }
    }

    // ---- Essential ----

    private static final class Essential extends WardrobeSource {
        @Override public String name() { return "Essential"; }
        @Override public boolean available() {
            // Deliberately not gated on the catalogue: Essential only sends that over its socket, so
            // requiring it here would hide the tab for good whenever the connection isn't up — and
            // the tab is where the reason for that is shown.
            return Config.get().essentialOn();
        }
        @Override protected Object state() { return EssentialAccount.state(); }
        @Override public String status() { return EssentialAccount.status(); }
        @Override public boolean busy() { return EssentialAccount.isBusy(); }
        @Override public void refresh() { EssentialAccount.refresh(); }
        @Override public boolean hasSettings() { return false; }

        @Override
        public int ownedCount() {
            // What the wardrobe lists: Minecraft capes and "Cape Disabled" are owned but not shown.
            return owned().size();
        }

        @Override
        public void toggle(Item item) {
            if (item.essentialId != null) {
                EssentialAccount.toggle(item.essentialId);
            }
        }

        @Override
        protected void build(Object raw, List<Item> owned, List<Item> equipped) {
            final EssentialAccount.State state = (EssentialAccount.State) raw;
            final EssentialCatalog catalog = EssentialCatalog.get();
            if (state == null || catalog == null) {
                return;
            }
            for (String id : state.owned) {
                final EssentialCatalog.Entry entry = catalog.entry(id);
                if (EssentialCatalog.isWearable(entry)) {
                    owned.add(item(entry, state));
                }
            }
            for (Map.Entry<String, String> slot : state.slots.entrySet()) {
                final EssentialCatalog.Entry entry = catalog.entry(slot.getValue());
                // An equipped Minecraft cape stays listed so it can be taken off; BASE_* (an empty
                // slot's default), emotes and "Cape Disabled" aren't cosmetics to show.
                if (entry == null || !(EssentialCatalog.isWearable(entry) || entry.isMojangCape())) {
                    continue;
                }
                equipped.add(item(entry, state));
            }
        }

        private static Item item(EssentialCatalog.Entry entry, EssentialAccount.State state) {
            // Everything Essential ships, capes included, is drawn from its own geometry, except
            // Minecraft capes, which only the vanilla cape layer shows.
            final boolean drawable = !entry.isMojangCape() && !entry.geometries().isEmpty() && entry.textureFor("") != null;
            return new Item("essential:" + entry.id, entry.name, entry.type, state.isEquipped(entry.id),
                    drawable, -1, null, entry.id);
        }

        @Override
        public void drawOutfit(EntityLivingBase entity, int x, int y, int w, int h, float yaw) {
            PreviewRenderer.drawEssential(entity, selfSnapshot(), PreviewRenderer.FULL, x, y, w, h, yaw);
        }

        @Override
        public void drawItem(EntityLivingBase entity, Item item, int x, int y, int w, int h) {
            final EssentialAccount.State state = EssentialAccount.state();
            final EssentialSnapshot live = selfSnapshot();
            if (state == null || item.essentialId == null) {
                return;
            }
            final Map<String, String> one = new java.util.LinkedHashMap<>();
            final String slot = state.slotOf(item.essentialId) != null ? state.slotOf(item.essentialId) : item.category;
            one.put(slot, item.essentialId);
            final java.util.Map<String, com.google.gson.JsonArray> settings =
                    live != null && live.settings.containsKey(item.essentialId)
                            ? Collections.singletonMap(item.essentialId, live.settings.get(item.essentialId))
                            : Collections.<String, com.google.gson.JsonArray>emptyMap();
            final java.util.UUID player = live != null ? live.player : EssentialCosmeticManager.selfId();
            final EssentialSnapshot preview = new EssentialSnapshot(player, one, settings);
            PreviewRenderer.drawEssential(entity, preview, PreviewRenderer.framingForEssential(item.category),
                    x, y, w, h, 0);
        }

        /** The live outfit, which also carries per-cosmetic settings the account state leaves out. */
        private static EssentialSnapshot selfSnapshot() {
            return EssentialCosmeticManager.outfit(EssentialCosmeticManager.selfId());
        }
    }

}
