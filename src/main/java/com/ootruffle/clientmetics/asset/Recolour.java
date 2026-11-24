package com.ootruffle.clientmetics.asset;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * Recolours a cosmetic's texture to a picked colour while keeping its shading and detail:
 * <ul>
 *     <li><b>Coloured textures</b> have their hues turned so the main colour (the average hue of
 *     the saturated pixels) lands on the picked hue. Every other colour turns by the same amount,
 *     so a two-tone item stays two-tone, and greys, blacks and whites (outlines, eyes, metal) stay
 *     as they are.</li>
 *     <li><b>Grey textures</b> (almost nothing saturated) are tinted instead: every pixel takes the
 *     picked hue, keeping its own brightness.</li>
 * </ul>
 * The picked colour's saturation and brightness scale the texture's own, so a fully saturated pick
 * (what the wardrobe's hue bar gives) leaves them alone.
 * <p>
 * Essential cosmetics that come in several colours are recoloured from one of their own coloured
 * textures instead, and only where those colours differ (see {@link #fromVariants}): the fabric
 * changes colour, drawstrings, zips and prints stay as they are.
 */
public final class Recolour {

    /** Pixels at least this saturated (and bright) count towards the main colour. */
    private static final float MIN_SATURATION = 0.25f;
    private static final float MIN_BRIGHTNESS = 0.15f;
    /** Below this share of saturated pixels a texture counts as grey and is tinted. */
    private static final float MIN_COLOURED_SHARE = 0.08f;
    /** How saturated a tinted grey texture gets, at most. */
    private static final float TINT_SATURATION = 0.7f;

    private Recolour() {}

    /** Pixels whose red, green and blue differ by more than this in total between two colours change colour. */
    private static final int VARIANT_DIFFERENCE = 24;

    /** Recolours every frame in place, all by the same amount (worked out from all of them together). */
    public static void apply(BufferedImage[] frames, int rgb) {
        apply(frames, rgb, null);
    }

    /**
     * Recolours {@code base} (a copy of the cosmetic's most colourful variant) in place, only where
     * the {@code variants} (the same texture in each of the cosmetic's colours, all base's size)
     * differ from one another. False, leaving it unchanged, if they aren't comparable or nothing differs.
     */
    public static boolean fromVariants(BufferedImage base, BufferedImage[] variants, int rgb) {
        final int w = base.getWidth();
        final int h = base.getHeight();
        final boolean[] mask = new boolean[w * h];
        int changing = 0;
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                final int first = variants[0].getRGB(px, py);
                for (int v = 1; v < variants.length; v++) {
                    final int other = variants[v].getRGB(px, py);
                    if (Math.abs(((first >> 16) & 0xFF) - ((other >> 16) & 0xFF))
                            + Math.abs(((first >> 8) & 0xFF) - ((other >> 8) & 0xFF))
                            + Math.abs((first & 0xFF) - (other & 0xFF)) > VARIANT_DIFFERENCE) {
                        mask[py * w + px] = true;
                        changing++;
                        break;
                    }
                }
            }
        }
        if (changing == 0) {
            return false;
        }
        apply(new BufferedImage[]{base}, rgb, mask);
        return true;
    }

    /** {@link #apply(BufferedImage[], int)}, only on the pixels {@code mask} marks (one frame), or all if null. */
    private static void apply(BufferedImage[] frames, int rgb, boolean[] mask) {
        final float[] target = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        // The main hue, as a weighted average around the colour wheel (so red at 0.99 and 0.01 averages to red).
        double x = 0;
        double y = 0;
        long opaque = 0;
        long coloured = 0;
        final float[] hsb = new float[3];
        for (BufferedImage frame : frames) {
            if (frame == null) {
                continue;
            }
            final int w = frame.getWidth();
            final int h = frame.getHeight();
            for (int py = 0; py < h; py++) {
                for (int px = 0; px < w; px++) {
                    if (mask != null && !mask[py * w + px]) {
                        continue;
                    }
                    final int argb = frame.getRGB(px, py);
                    if ((argb >>> 24) < 16) {
                        continue;
                    }
                    opaque++;
                    Color.RGBtoHSB((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, hsb);
                    if (hsb[1] >= MIN_SATURATION && hsb[2] >= MIN_BRIGHTNESS) {
                        coloured++;
                        final double weight = hsb[1] * hsb[2];
                        final double angle = hsb[0] * 2 * Math.PI;
                        x += Math.cos(angle) * weight;
                        y += Math.sin(angle) * weight;
                    }
                }
            }
        }
        if (opaque == 0) {
            return;
        }
        final boolean tint = coloured < opaque * MIN_COLOURED_SHARE || (x == 0 && y == 0);
        final float shift = tint ? 0 : target[0] - (float) (Math.atan2(y, x) / (2 * Math.PI));
        for (BufferedImage frame : frames) {
            if (frame == null) {
                continue;
            }
            final int w = frame.getWidth();
            final int h = frame.getHeight();
            for (int py = 0; py < h; py++) {
                for (int px = 0; px < w; px++) {
                    if (mask != null && !mask[py * w + px]) {
                        continue;
                    }
                    final int argb = frame.getRGB(px, py);
                    final int alpha = argb >>> 24;
                    if (alpha == 0) {
                        continue;
                    }
                    Color.RGBtoHSB((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, hsb);
                    final float hue;
                    final float saturation;
                    if (tint) {
                        hue = target[0];
                        saturation = target[1] * TINT_SATURATION;
                    } else {
                        hue = hsb[0] + shift; // HSBtoRGB wraps hues outside 0-1.
                        saturation = hsb[1] * target[1];
                    }
                    final float brightness = hsb[2] * target[2];
                    final int out = Color.HSBtoRGB(hue, clamp(saturation), clamp(brightness)) & 0xFFFFFF;
                    frame.setRGB(px, py, (alpha << 24) | out);
                }
            }
        }
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

}
