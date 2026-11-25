package com.ootruffle.clientmetics.model;

import com.ootruffle.clientmetics.asset.AssetFiles;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

/**
 * Which outer skin layer pixels an Essential cosmetic covers, from its {@code skin_mask.<model>.png}.
 *
 * <p>The mask is a 64x64 skin-layout image: white keeps a skin pixel, black hides it. Essential
 * uses it to hide the skin's outer layer under clothing (a hoodie hides most of the jacket and
 * sleeves), so the layer doesn't poke through. Masks only ever hide outer layers, and only the
 * outer-layer regions are read here.
 *
 * <p>The hidden pixels ({@link Mask#hidden}) are cleared from a copy of the wearer's skin (see
 * {@code MaskedSkins}), which vanilla, 3D Skin Layers and the Feminine Gender Mod all draw from, so
 * the rest of each layer stays. The per-part summary ({@link Mask#parts}: a part whose region is at
 * least {@link #THRESHOLD} hidden) says which parts the cosmetic really covers, for fitting a top
 * over the Feminine Gender Mod's breasts.
 */
public final class SkinCover {

    /** Fraction of a part's outer-layer region the mask must hide before the part counts as covered. */
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

    /** A mask that hides nothing. */
    public static final Mask NONE = new Mask(0, null);

    /**
     * What a mask hides: the parts it covers ({@link #HAT}...), and the outer-layer pixels it hides,
     * one row of the 64x64 layout per entry (bit x of {@code hidden[y]}), or null if none.
     */
    public static final class Mask {
        public final int parts;
        public final long[] hidden;

        Mask(int parts, long[] hidden) {
            this.parts = parts;
            this.hidden = hidden;
        }
    }

    private SkinCover() {}

    /** The mask in a file, or {@link #NONE} if it can't be read. Blocking; worker threads only. */
    static Mask read(String maskPath) {
        try {
            final BufferedImage mask = ImageIO.read(new ByteArrayInputStream(AssetFiles.read(maskPath)));
            return mask == null ? NONE : mask(mask);
        } catch (Exception e) {
            return NONE;
        }
    }

    /** What a mask image hides. */
    public static Mask mask(BufferedImage mask) {
        // Masks are 64x64; scale the regions if one ever isn't.
        final float sx = mask.getWidth() / 64f;
        final float sy = mask.getHeight() / 64f;
        final long[] rows = new long[64];
        boolean any = false;
        int parts = 0;
        for (int[] r : REGIONS) {
            int hidden = 0;
            int total = 0;
            for (int y = r[2]; y < r[4]; y++) {
                for (int x = r[1]; x < r[3]; x++) {
                    total++;
                    final int px = Math.min(mask.getWidth() - 1, (int) (x * sx));
                    final int py = Math.min(mask.getHeight() - 1, (int) (y * sy));
                    final int argb = mask.getRGB(px, py);
                    // Black (or transparent) hides; white keeps.
                    final int alpha = argb >>> 24;
                    final int luma = ((argb >> 16 & 0xFF) + (argb >> 8 & 0xFF) + (argb & 0xFF)) / 3;
                    if (alpha < 128 || luma < 128) {
                        hidden++;
                        rows[y] |= 1L << x;
                        any = true;
                    }
                }
            }
            if (total > 0 && hidden >= total * THRESHOLD) {
                parts |= r[0];
            }
        }
        return new Mask(parts, any ? rows : null);
    }

    /** Adds {@code mask}'s hidden pixels to {@code into} (64 rows), allocating it if null; returns it. */
    public static long[] union(long[] into, long[] mask) {
        if (mask == null) {
            return into;
        }
        final long[] out = into == null ? new long[64] : into;
        for (int y = 0; y < 64; y++) {
            out[y] |= mask[y];
        }
        return out;
    }

    /** Whether a pixel of the 64x64 layout is in {@code hidden} (null hides nothing). */
    public static boolean hides(long[] hidden, int x, int y) {
        return hidden != null && x >= 0 && x < 64 && y >= 0 && y < 64 && (hidden[y] >>> x & 1) != 0;
    }

    /**
     * A copy of a skin image with the hidden pixels made fully transparent. HD skins (a multiple of
     * 64 wide) are scaled to; a legacy 64x32 skin only has the hat rows.
     */
    public static BufferedImage apply(BufferedImage skin, long[] hidden) {
        final int w = skin.getWidth();
        final int h = skin.getHeight();
        final int[] pixels = skin.getRGB(0, 0, w, h, null, 0, w);
        final int s = Math.max(1, w / 64);
        for (int y = 0; y < 64; y++) {
            final long row = hidden[y];
            if (row == 0) {
                continue;
            }
            for (int x = 0; x < 64; x++) {
                if ((row >>> x & 1) == 0) {
                    continue;
                }
                for (int py = y * s; py < (y + 1) * s && py < h; py++) {
                    for (int px = x * s; px < (x + 1) * s && px < w; px++) {
                        pixels[py * w + px] &= 0x00FFFFFF;
                    }
                }
            }
        }
        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, pixels, 0, w);
        return out;
    }

}
