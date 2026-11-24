package com.ootruffle.clientmetics.render;

import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.asset.AssetFiles;
import com.ootruffle.clientmetics.asset.Textures;
import com.ootruffle.clientmetics.cosmetic.Recolours;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * The bell collar: Clientmetics' own cosmetic (its model and textures were made for this mod and
 * ship inside it), modelled on the collar painted on its owner's skin: a purple band round the top
 * of the body, a silver clasp at the back and a 2x2 bell at the front that swings as the player
 * moves and jingles (its own bundled sound) when it swings back hard.
 * <p>
 * Who wears it, and whether theirs jingles, comes from the colour server (see
 * {@link Recolours#wears}); the strap takes the colour picked for {@link Recolours#BELL_COLLAR_KEY}.
 * Every Clientmetics client works out the swing on its own and plays the chime itself, so it sounds
 * the same for everyone without sending anything about it.
 */
public final class BellCollar {

    private static final String STRAP = AssetFiles.BUNDLED + "cosmetics/bell_collar_strap.png";
    private static final String BELL = AssetFiles.BUNDLED + "cosmetics/bell_collar_bell.png";
    private static final String CLASP = AssetFiles.BUNDLED + "cosmetics/bell_collar_clasp.png";

    /** How hard the bell is pulled back to hanging straight, and how fast its swing dies down (per tick). */
    private static final float STIFFNESS = 0.16f;
    private static final float DAMPING = 0.12f;
    /** The farthest it swings, in degrees: away from the chest, and back into it (less, or it would go through). */
    private static final float MAX_OUT = 70f;
    private static final float MAX_IN = 12f;
    private static final float MAX_SIDE = 55f;
    /** It rings when it turns round at least this far out, at most every few ticks. */
    private static final float RING_ANGLE = 9f;
    private static final int RING_COOLDOWN_TICKS = 5;
    private static final float SNEAK_DROP = 0.2F;

    /** One player's bell. */
    private static final class Swing {
        float forward;
        float forwardSpeed;
        float side;
        float sideSpeed;
        float prevForward;
        float prevSide;
        double lastSpeedAlong;
        double lastMotionY;
        float lastYaw = Float.NaN;
        int cooldown;
    }

    private static final Map<EntityPlayer, Swing> SWINGS = new WeakHashMap<>();
    /** How many times a bell has rung (dev autotest). */
    public static int rings;

    /** The jingle, made for this mod and shipped inside it (see BundledResourcePack). */
    private static final ResourceLocation JINGLE = new ResourceLocation("clientmetics", "bell_collar");

    private BellCollar() {}

    /** Moves every bell in the world on by a tick, and rings the ones that swing hard enough. Client thread. */
    public static void onClientTick() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.isGamePaused()) {
            if (mc.theWorld == null) {
                SWINGS.clear();
            }
            return;
        }
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            final Boolean jingle = Recolours.wears(player.getUniqueID(), Recolours.BELL_COLLAR);
            if (jingle == null) {
                SWINGS.remove(player);
                continue;
            }
            Swing swing = SWINGS.get(player);
            if (swing == null) {
                swing = new Swing();
                SWINGS.put(player, swing);
            }
            // Sneaking keeps the bell quiet (it still swings).
            if (step(player, swing) && jingle && !player.isInvisible() && !player.isSneaking()) {
                final float strength = Math.min(1f, (Math.abs(swing.forward) + Math.abs(swing.side)) / 45f);
                rings++;
                playJingle(mc, player.posX, player.posY + 1.2, player.posZ,
                        0.07f + 0.18f * strength, 0.92f + player.getRNG().nextFloat() * 0.16f);
            }
        }
    }

    /**
     * Plays the bundled jingle ({@code clientmetics:bell_collar}, from the mod's sounds.json) at a spot
     * in the world, through the game's own sound handler so it works with whatever sound engine is in
     * use (vanilla's, or a replacement like SoundFix). Scaled by the game's Players volume and by
     * {@link Config#bellVolume} (this client only; 0 plays nothing).
     */
    private static void playJingle(Minecraft mc, double x, double y, double z, float volume, float pitch) {
        final int local = Math.max(0, Math.min(100, Config.get().bellVolume));
        if (local == 0) {
            return;
        }
        volume *= local / 100f;
        mc.getSoundHandler().playSound(new PositionedSoundRecord(JINGLE, volume, pitch, (float) x, (float) y, (float) z));
    }

    /** One tick of the pendulum. True when the bell should ring. */
    private static boolean step(EntityPlayer player, Swing s) {
        s.prevForward = s.forward;
        s.prevSide = s.side;
        // How the body moves, in its own frame: speed along where it faces, up and down, and turning.
        final float yaw = player.renderYawOffset;
        final double facingX = -MathHelper.sin(yaw * (float) Math.PI / 180f);
        final double facingZ = MathHelper.cos(yaw * (float) Math.PI / 180f);
        final double motionX = player.posX - player.prevPosX;
        final double motionZ = player.posZ - player.prevPosZ;
        final double motionY = player.posY - player.prevPosY;
        final double along = motionX * facingX + motionZ * facingZ;
        final float turn = Float.isNaN(s.lastYaw) ? 0 : MathHelper.wrapAngleTo180_float(yaw - s.lastYaw);
        final double accelAlong = along - s.lastSpeedAlong;
        final double accelY = motionY - s.lastMotionY;
        s.lastYaw = yaw;
        s.lastSpeedAlong = along;
        s.lastMotionY = motionY;

        // Starting to run leaves the bell behind (it swings back into the chest); stopping throws it
        // out. Jumps and landings jolt it, turning swings it to the side, and each step bobs it.
        final float walk = player.limbSwingAmount * MathHelper.cos(player.limbSwing * 0.6662f);
        final float pushForward = (float) (-accelAlong * 180 + Math.abs(accelY) * 60) + walk * 1.2f;
        final float pushSide = -turn * 0.35f + walk * 0.9f;
        final float wasForwardSpeed = s.forwardSpeed;
        final float wasSideSpeed = s.sideSpeed;
        s.forwardSpeed += pushForward - s.forward * STIFFNESS - s.forwardSpeed * DAMPING;
        s.sideSpeed += pushSide - s.side * STIFFNESS - s.sideSpeed * DAMPING;
        s.forward += s.forwardSpeed;
        s.side += s.sideSpeed;
        boolean knocked = false;
        if (s.forward < -MAX_IN) {
            s.forward = -MAX_IN;
            knocked = s.forwardSpeed < -3f; // Hit the chest hard enough to ring.
            s.forwardSpeed = -s.forwardSpeed * 0.4f;
        } else if (s.forward > MAX_OUT) {
            s.forward = MAX_OUT;
            s.forwardSpeed = 0;
        }
        if (Math.abs(s.side) > MAX_SIDE) {
            s.side = Math.signum(s.side) * MAX_SIDE;
            s.sideSpeed *= -0.4f;
        }
        if (s.cooldown > 0) {
            s.cooldown--;
            return false;
        }
        // The clapper hits the side of the bell when it turns round at the end of a big enough swing.
        final boolean turnedForward = Math.signum(wasForwardSpeed) != Math.signum(s.forwardSpeed) && Math.abs(s.forward) > RING_ANGLE;
        final boolean turnedSide = Math.signum(wasSideSpeed) != Math.signum(s.sideSpeed) && Math.abs(s.side) > RING_ANGLE;
        if (knocked || turnedForward || turnedSide) {
            s.cooldown = RING_COOLDOWN_TICKS;
            return true;
        }
        return false;
    }

    /** Draws the collar on this player if they wear it. In the player render layer, after the model. */
    public static void render(AbstractClientPlayer player, ModelPlayer model, float partialTicks) {
        if (Recolours.wears(player.getUniqueID(), Recolours.BELL_COLLAR) == null) {
            return;
        }
        if (player == Minecraft.getMinecraft().thePlayer && !Config.get().showOwn && !PreviewRenderer.isRendering()) {
            return;
        }
        final ResourceLocation strap = Textures.get(STRAP, Textures.Kind.MODEL,
                Recolours.colour(player.getUniqueID(), Recolours.BELL_COLLAR_KEY));
        final ResourceLocation bell = Textures.get(BELL, Textures.Kind.MODEL);
        final ResourceLocation clasp = Textures.get(CLASP, Textures.Kind.MODEL);
        if (strap == null || bell == null || clasp == null) {
            return;
        }
        final Swing swing = SWINGS.get(player);
        final float forward = swing == null ? 0 : swing.prevForward + (swing.forward - swing.prevForward) * partialTicks;
        final float side = swing == null ? 0 : swing.prevSide + (swing.side - swing.prevSide) * partialTicks;

        GlStateManager.pushMatrix();
        if (player.isSneaking()) {
            GlStateManager.translate(0, SNEAK_DROP, 0);
        }
        model.bipedBody.postRender(0.0625F);
        GlStateManager.scale(0.0625F, 0.0625F, 0.0625F); // Pixels from here: the body is x -4..4, y 0..12 (down), z -2..2.
        GlStateManager.color(1, 1, 1, 1);

        // Drawn like the collar painted on ootruffle's skin (where skins usually draw one): a band
        // one pixel tall round the top row of the body, a 2x2 bell at the front hanging one pixel
        // below it, and a clasp at the back. Here they stand just off the skin's outer layer (0.25 px).
        final float o = 0.3f;     // how far outside the body
        final float t = 0.25f;    // band thickness
        Minecraft.getMinecraft().getTextureManager().bindTexture(strap);
        box(-4 - o, 0, -2 - o, 4 + o, 1, -2 - o + t);
        box(-4 - o, 0, 2 + o - t, 4 + o, 1, 2 + o);
        box(4 + o - t, 0, -2 - o, 4 + o, 1, 2 + o);
        box(-4 - o, 0, -2 - o, -4 - o + t, 1, 2 + o);

        // The clasp: the 2x1 silver piece in the middle of the back, a little proud of the band.
        Minecraft.getMinecraft().getTextureManager().bindTexture(clasp);
        box(-1, 0, 2 + o, 1, 1, 2 + o + 0.2f);

        // The bell: 2x2, its top level with the band's, hanging from the middle of the front.
        Minecraft.getMinecraft().getTextureManager().bindTexture(bell);
        GlStateManager.translate(0, 0.15f, -2 - o);
        // Out (away from the chest) is towards -Z, so a positive forward angle turns it that way.
        GlStateManager.rotate(-forward, 1, 0, 0);
        GlStateManager.rotate(side, 0, 0, 1);
        box(-1, -0.15f, -1.5f, 1, 1.85f, 0);
        GlStateManager.popMatrix();
    }

    /** An axis-aligned box with the whole bound texture on every face. Pixel units, Y down. */
    private static void box(float x0, float y0, float z0, float x1, float y1, float z1) {
        final Tessellator tess = Tessellator.getInstance();
        final WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        // Front (-Z) and back (+Z).
        quad(wr, x0, y0, z0, 0, 0, x1, y0, z0, 1, 0, x1, y1, z0, 1, 1, x0, y1, z0, 0, 1, 0, 0, -1);
        quad(wr, x1, y0, z1, 0, 0, x0, y0, z1, 1, 0, x0, y1, z1, 1, 1, x1, y1, z1, 0, 1, 0, 0, 1);
        // Sides.
        quad(wr, x0, y0, z1, 0, 0, x0, y0, z0, 1, 0, x0, y1, z0, 1, 1, x0, y1, z1, 0, 1, -1, 0, 0);
        quad(wr, x1, y0, z0, 0, 0, x1, y0, z1, 1, 0, x1, y1, z1, 1, 1, x1, y1, z0, 0, 1, 1, 0, 0);
        // Top (-Y, the model's Y is down) and bottom, from the texture's top row.
        quad(wr, x0, y0, z1, 0, 0, x1, y0, z1, 1, 0, x1, y0, z0, 1, 0.125f, x0, y0, z0, 0, 0.125f, 0, -1, 0);
        quad(wr, x0, y1, z0, 0, 0.875f, x1, y1, z0, 1, 0.875f, x1, y1, z1, 1, 1, x0, y1, z1, 0, 1, 0, 1, 0);
        tess.draw();
    }

    private static void quad(WorldRenderer wr,
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
