package com.ootruffle.clientmetics.cosmetic;

import com.ootruffle.clientmetics.asset.Catalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The settings Lunar keeps on each equipped cosmetic, and which of them matter for each type,
 * for the wardrobe's settings panel. Changing one calls {@link LunarAccount}, which saves the
 * outfit on Lunar.
 */
public final class CosmeticOptions {

    /** A yes/no setting: an {@code EquippedCosmetic} bool field. */
    public static final class Flag {
        public final String label;
        public final String description;
        final int field;

        Flag(String label, String description, int field) {
            this.label = label;
            this.description = description;
            this.field = field;
        }

        public boolean get(EquippedCosmetic c) {
            switch (field) {
                case 3:
                    return c.clothCloak;
                case 4:
                    return c.showOverSkinLayer;
                case 5:
                    return c.showOverHelmet;
                case 6:
                    return c.showOverChestplate;
                case 7:
                    return c.showOverLeggings;
                case 8:
                    return c.showOverBoots;
                case 10:
                    return c.flipArm;
                default:
                    return false;
            }
        }

        /** Flips the setting and saves it to Lunar. */
        public void toggle(EquippedCosmetic c) {
            LunarAccount.setFlag(c.id, field, !get(c));
        }
    }

    /** A hat's height offset, within the range its model allows. */
    public static final class Height {
        public final float min;
        public final float max;
        public final float step;

        Height(float min, float max) {
            this.min = min;
            this.max = max;
            this.step = Math.max(0.05f, (max - min) / 30f);
        }

        /** The offset one step up (+1) or down (-1) from the current one, saved to Lunar. */
        public void nudge(EquippedCosmetic c, int direction) {
            final float next = Math.round(Math.max(min, Math.min(max, c.hatHeightOffset + direction * step)) * 100f) / 100f;
            if (next != c.hatHeightOffset) {
                LunarAccount.setHeightOffset(c.id, next);
            }
        }

        public void reset(EquippedCosmetic c) {
            if (c.hatHeightOffset != 0) {
                LunarAccount.setHeightOffset(c.id, 0);
            }
        }
    }

    private static final Flag OVER_HELMET = new Flag("Show over helmet", "Keep showing it while wearing a helmet.", 5);
    private static final Flag OVER_HAT_LAYER = new Flag("Show over hat layer", "Draw it over your skin's hat layer.", 4);
    private static final Flag OVER_CHESTPLATE = new Flag("Show over chestplate", "Keep showing it while wearing a chestplate.", 6);
    private static final Flag OVER_LEGGINGS = new Flag("Show over leggings", "Keep showing it while wearing leggings.", 7);
    private static final Flag OVER_BOOTS = new Flag("Show over boots", "Keep showing it while wearing boots.", 8);
    private static final Flag CLOTH = new Flag("Cloth physics", "Lunar Client draws it as a cloth cape (Clientmetics shows a normal cape).", 3);
    private static final Flag FLIP_ARM = new Flag("Other arm", "Hold it on the other arm.", 10);

    private CosmeticOptions() {}

    /** The yes/no settings that apply to this cosmetic, possibly none. */
    public static List<Flag> flags(Catalog.Entry entry) {
        if (entry == null) {
            return Collections.emptyList();
        }
        final List<Flag> out = new ArrayList<>();
        switch (entry.category) {
            case "hat":
            case "mask":
            case "glasses":
            case "bandanna":
                out.add(OVER_HELMET);
                out.add(OVER_HAT_LAYER);
                break;
            case "cloak":
                out.add(CLOTH);
                break;
            case "backpack":
            case "bodywear":
            case "neckwear":
            case "suits":
                out.add(OVER_CHESTPLATE);
                break;
            case "belts":
                out.add(OVER_LEGGINGS);
                break;
            case "shoes":
                out.add(OVER_BOOTS);
                break;
            case "shields":
            case "wristwear":
                out.add(FLIP_ARM);
                break;
            default:
                break;
        }
        return out;
    }

    /** The height setting, for hats whose model has a height range; else null. */
    public static Height height(Catalog.Entry entry) {
        final Catalog catalog = Catalog.get();
        if (entry == null || catalog == null || entry.geckolib) {
            return null;
        }
        final Catalog.ModelSettings settings = catalog.model(entry.modelName);
        if (settings == null || !(settings.minHeightOffset < settings.maxHeightOffset)) {
            return null;
        }
        return new Height(settings.minHeightOffset, settings.maxHeightOffset);
    }

    /** The equipped entry for this ID in the account's outfit, or null. */
    public static EquippedCosmetic equipped(LunarAccount.State state, int id) {
        if (state == null) {
            return null;
        }
        for (EquippedCosmetic c : state.equipped) {
            if (c.id == id) {
                return c;
            }
        }
        return null;
    }

}
