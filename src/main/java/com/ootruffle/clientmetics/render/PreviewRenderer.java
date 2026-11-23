package com.ootruffle.clientmetics.render;

import com.ootruffle.clientmetics.asset.Catalog;
import com.ootruffle.clientmetics.cosmetic.CosmeticManager;
import com.ootruffle.clientmetics.cosmetic.DawnCosmeticManager;
import com.ootruffle.clientmetics.cosmetic.DawnCosmeticSnapshot;
import com.ootruffle.clientmetics.cosmetic.EquippedCosmetic;
import com.ootruffle.clientmetics.cosmetic.EssentialCosmeticManager;
import com.ootruffle.clientmetics.cosmetic.EssentialSnapshot;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLivingBase;
import org.lwjgl.opengl.GL11;

/**
 * Draws a player into a GUI at any angle and zoom, optionally wearing a given outfit instead of
 * their real one (the wardrobe's card thumbnails show one cosmetic each). Render thread only.
 * <p>
 * While a preview is drawn, the outfit override replaces the player's outfit in
 * {@link CosmeticRenderer} and {@link Cloaks}, and armor no longer hides cosmetics.
 */
public final class PreviewRenderer {

    /** How a preview frames the player: which height is centred and how much it zooms. */
    public static final class Framing {
        /** Centred height, as a fraction of the player's height (0 = feet, 1 = top of the head). */
        public final float focus;
        /** 1 = the whole player fits the box's height. */
        public final float zoom;
        /** Degrees; 0 faces the viewer, 180 shows the back. */
        public final float yaw;

        public Framing(float focus, float zoom, float yaw) {
            this.focus = focus;
            this.zoom = zoom;
            this.yaw = yaw;
        }
    }

    public static final Framing FULL = new Framing(0.52f, 0.86f, 25);
    private static final Framing HEAD = new Framing(0.9f, 2.2f, 30);
    private static final Framing TORSO = new Framing(0.6f, 1.8f, 20);
    private static final Framing BACK = new Framing(0.62f, 1.35f, 205);
    private static final Framing WINGS = new Framing(0.62f, 1.05f, 215);
    private static final Framing FEET = new Framing(0.15f, 2.4f, 30);
    private static final Framing PET = new Framing(0.7f, 1.2f, 35);

    /** The rendered player (scaled 0.9375 by the player renderer) plus room for hats. */
    private static final float PLAYER_HEIGHT = 1.95f;

    /** How wide the player is (with a cloak or wings), so a narrow box still fits the whole player. */
    private static final float PLAYER_WIDTH = 1.15f;

    private static List<EquippedCosmetic> override;
    /** Dawn slugs drawn with {@link #override}; empty for Lunar previews. */
    private static Collection<String> dawnOverride;
    private static EssentialSnapshot essentialOverride;
    private static UUID overrideFor;
    private static boolean rendering;

    private PreviewRenderer() {}

    /** The outfit to draw for this player: a preview's override, or what Lunar says they wear. */
    public static List<EquippedCosmetic> outfitFor(UUID uuid) {
        if (override != null && uuid != null && uuid.equals(overrideFor)) {
            return override;
        }
        return CosmeticManager.outfit(uuid);
    }

    /** The Dawn slugs to draw for this player: a preview's override, or what Dawn says they wear (null if nothing). */
    public static Collection<String> dawnOutfitFor(UUID uuid) {
        if (override != null && uuid != null && uuid.equals(overrideFor)) {
            return dawnOverride;
        }
        final DawnCosmeticSnapshot snapshot = DawnCosmeticManager.outfit(uuid);
        return snapshot == null ? null : snapshot.equipped.values();
    }

    /** The Essential outfit to draw for this player, or null if they aren't known to be on Essential. */
    public static EssentialSnapshot essentialOutfitFor(UUID uuid) {
        if (essentialOverride != null && uuid != null && uuid.equals(overrideFor)) {
            return essentialOverride;
        }
        return EssentialCosmeticManager.outfit(uuid);
    }

    /** Whether a preview with an outfit override is being drawn (armor shouldn't hide cosmetics in previews). */
    public static boolean isPreviewing() {
        return override != null;
    }
    /** Whether any wardrobe preview is being drawn right now (nametags are hidden then). */
    public static boolean isRendering() {
        return rendering;
    }

    /** The framing that shows this kind of Dawn cosmetic best ({@code DawnCatalog.Entry.type}). */
    public static Framing framingForDawn(String type) {
        if (type == null) {
            return FULL;
        }
        switch (type) {
            case "hat":
            case "face":
                return HEAD;
            case "cape":
            case "back":
                return BACK;
            case "wing":
                return WINGS;
            case "arm":
            case "shield":
                return TORSO;
            case "boot":
                return FEET;
            case "pet":
                return PET;
            default:
                return FULL;
        }
    }

    /** The framing that shows this kind of Essential cosmetic best. */
    public static Framing framingForEssential(String type) {
        if (type == null) return FULL;
        switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case "HAT": case "HEAD": case "FACE": case "EARS": return HEAD;
            case "CAPE": case "BACK": case "TAIL": return BACK;
            case "WINGS": return WINGS;
            case "ARMS": case "SHOULDERS": case "TOP": case "SUITS": case "FULL_BODY": return TORSO;
            case "SHOES": case "PANTS": case "SKIRT": return FEET;
            case "PET": return PET;
            default: return FULL;
        }
    }

    /** The framing that shows this kind of cosmetic best. */
    public static Framing framingFor(Catalog.Entry entry) {
        if (entry == null) {
            return FULL;
        }
        switch (entry.category) {
            case "hat":
            case "mask":
            case "glasses":
            case "bandanna":
                return HEAD;
            case "cloak":
            case "backpack":
                return BACK;
            case "dragon_wings":
                return WINGS;
            case "bodywear":
            case "neckwear":
            case "belts":
            case "suits":
            case "wristwear":
                return TORSO;
            case "shoes":
                return FEET;
            case "pet":
            case "companion":
                return PET;
            default:
                return FULL;
        }
    }

    /**
     * Draws {@code entity} centred in the box, wearing {@code outfit} (null = their real outfit).
     * {@code extraYaw} turns it (for dragging), added to the framing's own angle.
     */
    public static void draw(EntityLivingBase entity, List<EquippedCosmetic> outfit, Framing framing,
                            int boxX, int boxY, int boxW, int boxH, float extraYaw) {
        draw(entity, outfit, outfit == null ? null : Collections.<String>emptyList(), framing, boxX, boxY, boxW, boxH, extraYaw);
    }

    /** Like {@link #draw}, but wearing only these Dawn cosmetics (by slug), and no Lunar ones. */
    public static void drawDawn(EntityLivingBase entity, Collection<String> slugs, Framing framing,
                                int boxX, int boxY, int boxW, int boxH, float extraYaw) {
        draw(entity, Collections.<EquippedCosmetic>emptyList(), slugs == null ? Collections.<String>emptyList() : slugs,
                framing, boxX, boxY, boxW, boxH, extraYaw);
    }

    /** Draws the player wearing the supplied Essential snapshot. */
    public static void drawEssential(EntityLivingBase entity, EssentialSnapshot outfit, Framing framing,
                                     int boxX, int boxY, int boxW, int boxH, float extraYaw) {
        draw(entity, Collections.<EquippedCosmetic>emptyList(), Collections.<String>emptyList(), outfit,
                framing, boxX, boxY, boxW, boxH, extraYaw);
    }

    private static void draw(EntityLivingBase entity, List<EquippedCosmetic> outfit, Collection<String> dawn, Framing framing,
                             int boxX, int boxY, int boxW, int boxH, float extraYaw) {
        draw(entity, outfit, dawn, null, framing, boxX, boxY, boxW, boxH, extraYaw);
    }

    private static void draw(EntityLivingBase entity, List<EquippedCosmetic> outfit, Collection<String> dawn,
                             EssentialSnapshot essential, Framing framing,
                             int boxX, int boxY, int boxW, int boxH, float extraYaw) {
        // Fit the height, unless the box is too narrow for the player (a tall panel on a wide screen).
        final float scale = Math.min(boxH / PLAYER_HEIGHT, boxW / PLAYER_WIDTH) * 0.9f * framing.zoom;
        final float feetY = boxY + boxH / 2f + framing.focus * PLAYER_HEIGHT * scale;
        final float yaw = framing.yaw + extraYaw;

        override = outfit;
        dawnOverride = dawn;
        essentialOverride = essential;
        overrideFor = outfit == null ? null : entity.getUniqueID();
        rendering = true;
        final float renderYawOffset = entity.renderYawOffset;
        final float prevRenderYawOffset = entity.prevRenderYawOffset;
        final float rotationYaw = entity.rotationYaw;
        final float prevRotationYaw = entity.prevRotationYaw;
        final float rotationPitch = entity.rotationPitch;
        final float prevRotationPitch = entity.prevRotationPitch;
        final float rotationYawHead = entity.rotationYawHead;
        final float prevRotationYawHead = entity.prevRotationYawHead;
        // No red damage flash in previews.
        final int hurtTime = entity.hurtTime;
        final int deathTime = entity.deathTime;
        entity.hurtTime = 0;
        entity.deathTime = 0;
        try {
            GlStateManager.enableColorMaterial();
            GlStateManager.pushMatrix();
            GlStateManager.translate(boxX + boxW / 2f, feetY, 50);
            GlStateManager.scale(-scale, scale, scale);
            GlStateManager.rotate(180, 0, 0, 1);
            GlStateManager.rotate(135, 0, 1, 0);
            RenderHelper.enableStandardItemLighting();
            GlStateManager.rotate(-135, 0, 1, 0);
            GlStateManager.rotate(-8, 1, 0, 0);
            GlStateManager.rotate(yaw, 0, 1, 0);
            entity.renderYawOffset = entity.prevRenderYawOffset = 0;
            entity.rotationYaw = entity.prevRotationYaw = 0;
            entity.rotationYawHead = entity.prevRotationYawHead = 0;
            entity.rotationPitch = entity.prevRotationPitch = 0;
            // Full brightness, so previews don't go dark at night or underground.
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
            // GUI drawRect leaves the GL colour at the last rectangle's colour, which the player
            // renderer would multiply the model by: reset it to white.
            GlStateManager.color(1, 1, 1, 1);
            final RenderManager rm = Minecraft.getMinecraft().getRenderManager();
            rm.setPlayerViewY(180);
            rm.setRenderShadow(false);
            rm.renderEntityWithPosYaw(entity, 0, 0, 0, 0, 1);
            rm.setRenderShadow(true);
            GlStateManager.popMatrix();
        } finally {
            entity.renderYawOffset = renderYawOffset;
            entity.prevRenderYawOffset = prevRenderYawOffset;
            entity.rotationYaw = rotationYaw;
            entity.prevRotationYaw = prevRotationYaw;
            entity.rotationPitch = rotationPitch;
            entity.prevRotationPitch = prevRotationPitch;
            entity.rotationYawHead = rotationYawHead;
            entity.prevRotationYawHead = prevRotationYawHead;
            entity.hurtTime = hurtTime;
            entity.deathTime = deathTime;
            override = null;
            dawnOverride = null;
            essentialOverride = null;
            overrideFor = null;
            rendering = false;
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.disableTexture2D();
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.enableTexture2D();
            // So the next card's model or the text after it isn't hidden behind this one.
            GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
        }
    }

}
