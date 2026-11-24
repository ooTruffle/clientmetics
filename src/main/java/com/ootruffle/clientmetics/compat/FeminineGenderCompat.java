package com.ootruffle.clientmetics.compat;

import com.ootruffle.clientmetics.asset.Textures;
import com.ootruffle.clientmetics.model.GeoCosmetic;
import com.ootruffle.clientmetics.model.SkinCover;
import com.ootruffle.clientmetics.render.CosmeticRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.ResourceLocation;

/**
 * Feminine Gender Mod support, found at runtime (see {@code FeminineGenderLayerMixin}).
 *
 * <p>When an Essential cosmetic covers the torso, the cosmetic is drawn over the mod's breasts, the
 * way the mod fits armour over them: inside the mod's own per-breast transforms (position, size,
 * bounce, rotation), each breast gets a box a little larger than the mod's for each of the
 * cosmetic's body-sized cubes, textured from that cube's chest. The mod's breast front is the skin's body-front pixels, and
 * Essential's body cubes use the same box layout, so the same face of the cosmetic's texture is the
 * matching part of the top. The mod's jacket overlay is already hidden then (the jacket layer reads
 * as not worn, see {@link SkinCover}).
 *
 * <p>A cosmetic that covers the torso but has no body-sized cube can't be fitted, so the breasts
 * are hidden instead, as the mod does under a chestplate.
 */
public final class FeminineGenderCompat {

    /** How far the cosmetic's breast box sits outside the cosmetic's own body cube, in pixels. */
    private static final float EXTRA_INFLATE = 0.1f;

    /** How often the mod's layer asked, and how many breasts were covered (dev autotest). */
    public static volatile int calls;
    public static volatile int covered;

    /** The player and cosmetic of the layer being drawn now. Render thread only. */
    private static AbstractClientPlayer player;
    private static GeoCosmetic cover;

    private FeminineGenderCompat() {}

    /**
     * Start of the mod's layer for a player. Returns true to skip the layer (a torso-covering
     * cosmetic that can't be fitted), false to draw it (with {@link #coverBreast} for each breast).
     */
    public static boolean beginLayer(AbstractClientPlayer target) {
        calls++;
        player = target;
        cover = null;
        if (target == null || !CosmeticRenderer.coversSkinLayer(target, SkinCover.JACKET)) {
            return false;
        }
        cover = CosmeticRenderer.torsoCover(target);
        return cover == null;
    }

    /** Whether the mod's layer would be skipped for this player (dev autotest). */
    public static boolean hideUnderCosmetic(AbstractClientPlayer target) {
        return target != null && CosmeticRenderer.coversSkinLayer(target, SkinCover.JACKET)
                && CosmeticRenderer.torsoCover(target) == null;
    }

    /** The cosmetic drawn over this player's breasts, or null (dev autotest). */
    public static GeoCosmetic coverFor(AbstractClientPlayer target) {
        return target != null && CosmeticRenderer.coversSkinLayer(target, SkinCover.JACKET)
                ? CosmeticRenderer.torsoCover(target) : null;
    }

    /**
     * Called in the mod's transform for one breast, before it draws it: draws the cosmetic's box
     * there, then puts the player's skin back for the mod.
     */
    public static void coverBreast(boolean left, float scale) {
        final GeoCosmetic geo = cover;
        final AbstractClientPlayer target = player;
        if (geo == null || target == null || geo.torsoFront == null) {
            return;
        }
        final String id = CosmeticRenderer.essentialIdOf(target, geo);
        final ResourceLocation texture = Textures.get(geo.texturePath, Textures.Kind.MODEL,
                id == null ? -1 : com.ootruffle.clientmetics.cosmetic.Recolours.colour(target.getUniqueID(), "essential:" + id));
        if (texture == null) {
            return;
        }
        Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
        // Each body-sized layer of the cosmetic, inner first: a solid top, then its overlay.
        for (float[] layer : geo.torsoFront) {
            drawBox(layer, left, scale);
        }
        Minecraft.getMinecraft().getTextureManager().bindTexture(target.getLocationSkin());
        covered++;
    }

    /**
     * Draws the mod's breast box (4x5x3 pixels at x -4 or 0, as its own boxes, model space with Y
     * down), inflated past the cosmetic's body cube, every face textured from that breast's part of
     * the cosmetic's chest: the mod's skin UVs use rows 1-6 of the body front, the left breast its
     * first four columns and the right the next. The sides, top and bottom stretch the chest's edge
     * pixels, so no face samples an unrelated (often transparent) part of the texture. The back is
     * inside the body and left out.
     */
    private static void drawBox(float[] front, boolean left, float scale) {
        final float g = front[2] + EXTRA_INFLATE;
        final float x0 = ((left ? -4 : 0) - g) * scale;
        final float x1 = ((left ? 0 : 4) + g) * scale;
        final float y0 = -g * scale;
        final float y1 = (5 + g) * scale;
        final float z0 = -g * scale;
        final float z1 = (3 + g) * scale;
        final float tw = front[3];
        final float th = front[4];
        final float u0 = (front[0] + (left ? 0 : 4)) / tw;
        final float u1 = (front[0] + (left ? 4 : 8)) / tw;
        final float v0 = (front[1] + 1) / th;
        final float v1 = (front[1] + 6) / th;
        final float du = 1 / tw;
        final float dv = 1 / th;
        final net.minecraft.client.renderer.Tessellator tess = net.minecraft.client.renderer.Tessellator.getInstance();
        final net.minecraft.client.renderer.WorldRenderer wr = tess.getWorldRenderer();
        // Culling may be on here (the mod's layer) or off (the player renderer): draw both sides,
        // then put back exactly what was there.
        final boolean cull = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_CULL_FACE);
        net.minecraft.client.renderer.GlStateManager.disableCull();
        wr.begin(org.lwjgl.opengl.GL11.GL_QUADS, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_TEX_NORMAL);
        // Front (towards -Z): the chest itself.
        quad(wr, x0, y0, z0, u0, v0, x1, y0, z0, u1, v0, x1, y1, z0, u1, v1, x0, y1, z0, u0, v1, 0, 0, -1);
        // Top and bottom: the chest's top and bottom rows.
        quad(wr, x0, y0, z1, u0, v0, x1, y0, z1, u1, v0, x1, y0, z0, u1, v0 + dv, x0, y0, z0, u0, v0 + dv, 0, -1, 0);
        quad(wr, x0, y1, z0, u0, v1 - dv, x1, y1, z0, u1, v1 - dv, x1, y1, z1, u1, v1, x0, y1, z1, u0, v1, 0, 1, 0);
        // Sides: the chest's outer columns.
        quad(wr, x0, y0, z1, u0, v0, x0, y0, z0, u0 + du, v0, x0, y1, z0, u0 + du, v1, x0, y1, z1, u0, v1, -1, 0, 0);
        quad(wr, x1, y0, z0, u1 - du, v0, x1, y0, z1, u1, v0, x1, y1, z1, u1, v1, x1, y1, z0, u1 - du, v1, 1, 0, 0);
        tess.draw();
        if (cull) {
            net.minecraft.client.renderer.GlStateManager.enableCull();
        }
    }

    private static void quad(net.minecraft.client.renderer.WorldRenderer wr,
                             float ax, float ay, float az, float au, float av,
                             float bx, float by, float bz, float bu, float bv,
                             float cx, float cy, float cz, float cu, float cv,
                             float dx, float dy, float dz, float du, float dv,
                             float nx, float ny, float nz) {
        wr.pos(ax, ay, az).tex(au, av).normal(nx, ny, nz).endVertex();
        wr.pos(bx, by, bz).tex(bu, bv).normal(nx, ny, nz).endVertex();
        wr.pos(cx, cy, cz).tex(cu, cv).normal(nx, ny, nz).endVertex();
        wr.pos(dx, dy, dz).tex(du, dv).normal(nx, ny, nz).endVertex();
    }

}
