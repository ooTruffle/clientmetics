package com.ootruffle.clientmetics.model;

import com.ootruffle.clientmetics.asset.AssetFiles;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import net.minecraft.entity.player.EnumPlayerModelParts;

/**
 * Which outer skin layers an Essential cosmetic covers, from its {@code skin_mask.<model>.png}.
 *
 * <p>The mask is a 64x64 skin-layout image: white keeps a skin pixel, black hides it. Essential
 * uses it to hide the skin's outer layer under clothing (a hoodie hides most of the jacket and
 * sleeves), so the layer doesn't poke through. Mods like 3D Skin Layers turn that layer into
 * voxels built from the downloaded skin, which a per-pixel mask can't reach, so this works per
 * part instead: a part whose region is at least {@link #THRESHOLD} hidden is reported as not worn
 * (see {@code EntityPlayerSkinCoverMixin}), which vanilla, 3D Skin Layers and the Feminine Gender
 * Mod's overlay all respect. Masks only ever hide outer layers.
 */
public final class SkinCover {

    /** Fraction of a part's outer-layer region the mask must hide before the whole part is hidden. */
    static final float THRESHOLD = 0.35f;

    public static final int HAT = 1;
    public static final int JACKET = 1 << 1;
    public static final int LEFT_SLEEVE = 1 << 2;
    public static final int RIGHT_SLEEVE = 1 << 3;
    public static final int LEFT_PANTS = 1 << 4;
    public static final int RIGHT_PANTS = 1 << 5;

    /** {bit, x0, y0, x1, y1} on the 64x64 skin layout (the player's right is the skin's "right"). */
    private static final int[][] REGIONS = {
            {HAT, 32, 0, 64, 16},
            {JACKET, 16, 32, 40, 48},
            {RIGHT_SLEEVE, 40, 32, 56, 48},
            {RIGHT_PANTS, 0, 32, 16, 48},
            {LEFT_PANTS, 0, 48, 16, 64},
            {LEFT_SLEEVE, 48, 48, 64, 64},
    };

    private SkinCover() {}

    /** The parts a mask file covers, or 0 if it can't be read. Blocking; worker threads only. */
    static int read(String maskPath) {
        try {
            final BufferedImage mask = ImageIO.read(new ByteArrayInputStream(AssetFiles.read(maskPath)));
            return mask == null ? 0 : covered(mask);
        } catch (Exception e) {
            return 0;
        }
    }

    /** The parts a mask image covers. */
    public static int covered(BufferedImage mask) {
        // Masks are 64x64; scale the regions if one ever isn't.
        final float sx = mask.getWidth() / 64f;
        final float sy = mask.getHeight() / 64f;
        int out = 0;
        for (int[] r : REGIONS) {
            final int x0 = Math.round(r[1] * sx);
            final int y0 = Math.round(r[2] * sy);
            final int x1 = Math.min(mask.getWidth(), Math.round(r[3] * sx));
            final int y1 = Math.min(mask.getHeight(), Math.round(r[4] * sy));
            int hidden = 0;
            int total = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    final int argb = mask.getRGB(x, y);
                    total++;
                    // Black (or transparent) hides; white keeps.
                    final int alpha = argb >>> 24;
                    final int luma = ((argb >> 16 & 0xFF) + (argb >> 8 & 0xFF) + (argb & 0xFF)) / 3;
                    if (alpha < 128 || luma < 128) {
                        hidden++;
                    }
                }
            }
            if (total > 0 && hidden >= total * THRESHOLD) {
                out |= r[0];
            }
        }
        return out;
    }

    /** The bit for a vanilla model part, or 0 for the cape (never covered). */
    public static int bit(EnumPlayerModelParts part) {
        switch (part) {
            case HAT:
                return HAT;
            case JACKET:
                return JACKET;
            case LEFT_SLEEVE:
                return LEFT_SLEEVE;
            case RIGHT_SLEEVE:
                return RIGHT_SLEEVE;
            case LEFT_PANTS_LEG:
                return LEFT_PANTS;
            case RIGHT_PANTS_LEG:
                return RIGHT_PANTS;
            default:
                return 0;
        }
    }

}
