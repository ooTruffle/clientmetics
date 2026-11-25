package com.ootruffle.clientmetics.render;

import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.model.SkinCover;
import java.awt.image.BufferedImage;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Hides the outer skin layer under Essential clothing pixel by pixel, the way Essential does: a
 * player wearing cosmetics with skin masks (see {@link SkinCover}) is given a copy of their skin
 * with the covered outer-layer pixels made transparent, through {@code getLocationSkin} (see
 * {@code AbstractClientPlayerSkinMixin}). The rest of each layer stays: a skirt hides the trouser
 * pixels under it, not the whole trouser layer.
 *
 * <p>Everything that draws the skin asks for that texture: the player renderer, first-person arms,
 * the Feminine Gender Mod, and 3D Skin Layers, which builds its voxels from the image behind it.
 * The copy is a {@link ThreadDownloadImageData} holding its image, the same kind of texture as a
 * downloaded skin, so 3D Skin Layers can read it like one.
 *
 * <p>Copies are shared by every player with the same skin and mask, made once, and dropped a while
 * after nobody draws them. Render thread only.
 */
public final class MaskedSkins {

    /** How long an unused copy is kept. */
    private static final long FORGET_NANOS = 30_000_000_000L;
    /** How often unused copies are looked for. */
    private static final long SWEEP_NANOS = 5_000_000_000L;
    /** How long to wait before trying a skin again that couldn't be read (one still loading). */
    private static final long RETRY_NANOS = 2_000_000_000L;

    private static final Map<Key, Entry> CACHE = new HashMap<>();
    private static int nextId;
    private static long lastSweep;
    private static boolean failureLogged;

    private MaskedSkins() {}

    /** The skin to draw this player with: a masked copy of {@code original}, or {@code original} itself. */
    public static ResourceLocation skinFor(AbstractClientPlayer player, ResourceLocation original) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (original == null || mc == null || !mc.isCallingFromMinecraftThread()) {
            return original;
        }
        final long[] hidden = CosmeticRenderer.skinMask(player);
        if (hidden == null) {
            return original;
        }
        final long now = System.nanoTime();
        if (now - lastSweep > SWEEP_NANOS) {
            sweep(mc, now);
        }
        final Key key = new Key(original, hidden);
        Entry entry = CACHE.get(key);
        if (entry == null || entry.location == null && now - entry.built > RETRY_NANOS) {
            entry = build(mc, key, now);
            CACHE.put(key, entry);
        }
        entry.lastUsed = now;
        return entry.location != null ? entry.location : original;
    }

    /** {source, masked} images behind a masked copy, or null if {@code location} isn't one (dev autotest). */
    public static BufferedImage[] imagesOf(ResourceLocation location) {
        for (Entry entry : CACHE.values()) {
            if (location != null && location.equals(entry.location)) {
                return new BufferedImage[]{entry.source, entry.masked};
            }
        }
        return null;
    }

    private static Entry build(Minecraft mc, Key key, long now) {
        final int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            // Read the skin back from its GL texture: that works whatever kind of texture it is
            // (a downloaded skin, a resource-pack default, another mod's), once it's uploaded.
            mc.getTextureManager().bindTexture(key.skin);
            final int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            final int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (w < 64 || w % 64 != 0 || (h != w && h != w / 2)) {
                return failed(now, key, "unexpected size " + w + "x" + h);
            }
            final IntBuffer buffer = BufferUtils.createIntBuffer(w * h);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
            final int[] pixels = new int[w * h];
            buffer.get(pixels);
            final BufferedImage source = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            source.setRGB(0, 0, w, h, pixels, 0, w);
            final BufferedImage masked = SkinCover.apply(source, key.hidden);
            final ResourceLocation location = new ResourceLocation("clientmetics", "masked_skin/" + nextId++);
            mc.getTextureManager().loadTexture(location, new MaskedTexture(masked));
            return new Entry(location, source, masked, now);
        } catch (RuntimeException e) {
            return failed(now, key, e.toString());
        } finally {
            GlStateManager.bindTexture(previous);
        }
    }

    private static Entry failed(long now, Key key, String why) {
        if (!failureLogged) {
            failureLogged = true;
            Clientmetics.LOGGER.warn("Couldn't mask skin {} under cosmetics ({}); drawing it whole", key.skin, why);
        }
        return new Entry(null, null, null, now);
    }

    /** Drops the copies nobody has drawn for a while. */
    private static void sweep(Minecraft mc, long now) {
        lastSweep = now;
        for (Iterator<Entry> it = CACHE.values().iterator(); it.hasNext(); ) {
            final Entry entry = it.next();
            if (now - entry.lastUsed > FORGET_NANOS) {
                if (entry.location != null) {
                    mc.getTextureManager().deleteTexture(entry.location);
                }
                it.remove();
            }
        }
    }

    /** A skin and the pixels hidden from it. */
    private static final class Key {
        final ResourceLocation skin;
        final long[] hidden;
        private final int hash;

        Key(ResourceLocation skin, long[] hidden) {
            this.skin = skin;
            this.hidden = hidden;
            this.hash = skin.hashCode() * 31 + Arrays.hashCode(hidden);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && skin.equals(((Key) o).skin) && Arrays.equals(hidden, ((Key) o).hidden);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class Entry {
        /** Null when the skin couldn't be read (retried after {@link #RETRY_NANOS}). */
        final ResourceLocation location;
        final BufferedImage source;
        final BufferedImage masked;
        final long built;
        long lastUsed;

        Entry(ResourceLocation location, BufferedImage source, BufferedImage masked, long built) {
            this.location = location;
            this.source = source;
            this.masked = masked;
            this.built = built;
            this.lastUsed = built;
        }
    }

    /** A skin texture that already holds its image: uploaded on first use, nothing to download. */
    private static final class MaskedTexture extends ThreadDownloadImageData {

        MaskedTexture(BufferedImage image) {
            super(null, null, null, null);
            setBufferedImage(image);
        }

        @Override
        public void loadTexture(IResourceManager manager) {
            // The image is set already; the downloading texture would fetch a URL here.
        }

    }

}
