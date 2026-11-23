package com.ootruffle.clientmetics.asset;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

/**
 * Cosmetic textures: decoded off-thread (WebP via TwelveMonkeys, since 1.8.9's ImageIO has no
 * WebP reader), split into animation frames using the {@code .mcmeta} next to them, and uploaded
 * on the render thread a few frames per tick so a long animation doesn't stall the game.
 */
public final class Textures {

    /** How a texture is laid out for the model that uses it. */
    public enum Kind {
        /** Used as-is by an OBJ or GeckoLib model. */
        MODEL,
        /**
         * A cloak: Lunar crops each frame to the 22x17 region the cape model reads, so it's put back
         * on a 64x32 canvas for the vanilla cape layer, and scaled down past 8x so long animations
         * don't use hundreds of MB of video memory.
         */
        CLOAK
    }

    private static final int MAX_CLOAK_SCALE = 8;
    private static final int UPLOADS_PER_TICK = 8;

    private static final class Decoded {
        final BufferedImage[] frames;
        final int frameTimeTicks;

        Decoded(BufferedImage[] frames, int frameTimeTicks) {
            this.frames = frames;
            this.frameTimeTicks = Math.max(1, frameTimeTicks);
        }
    }

    private static final class Uploaded {
        final Kind kind;
        final String path;
        final Decoded source;
        final ResourceLocation[] frames;
        int uploaded;
        long lastUsed;

        Uploaded(Kind kind, String path, Decoded source) {
            this.kind = kind;
            this.path = path;
            this.source = source;
            this.frames = new ResourceLocation[source.frames.length];
        }

        void delete() {
            for (ResourceLocation frame : frames) {
                if (frame != null) {
                    Minecraft.getMinecraft().getTextureManager().deleteTexture(frame);
                }
            }
            // The decoded pixels were dropped on upload: forget them so a later use decodes again.
            Assets.forget("texture-" + kind, path);
        }
    }

    private static final Map<String, Uploaded> UPLOADED = new ConcurrentHashMap<>();
    private static int uploadBudget = UPLOADS_PER_TICK;
    private static int nextId;

    private Textures() {}

    /** Called once per client tick; resets the upload budget. */
    public static void onTick() {
        uploadBudget = UPLOADS_PER_TICK;
    }

    /** Starts decoding a texture without uploading it. Any thread. */
    public static void preload(String path, Kind kind) {
        Assets.get("texture-" + kind, path, p -> decode(p, kind));
    }

    /** The texture's current animation frame, or null if it isn't ready (or doesn't exist). Render thread only. */
    public static ResourceLocation get(String path, Kind kind) {
        final String key = kind + ":" + path;
        Uploaded up = UPLOADED.get(key);
        if (up == null) {
            final Decoded decoded = Assets.get("texture-" + kind, path, p -> decode(p, kind));
            if (decoded == null || decoded.frames.length == 0) {
                return null;
            }
            up = new Uploaded(kind, path, decoded);
            UPLOADED.put(key, up);
        }
        up.lastUsed = Minecraft.getSystemTime();
        while (up.uploaded < up.frames.length && uploadBudget > 0) {
            uploadBudget--;
            final DynamicTexture texture = new DynamicTexture(up.source.frames[up.uploaded]);
            // Our own namespace, so other mods (PolyPlus's "Show over other mods") can tell these
            // textures come from Clientmetics.
            final ResourceLocation location = new ResourceLocation("clientmetics", "dynamic/" + nextId++);
            Minecraft.getMinecraft().getTextureManager().loadTexture(location, texture);
            up.frames[up.uploaded] = location;
            up.source.frames[up.uploaded] = null; // The pixels live on the GPU now.
            up.uploaded++;
        }
        if (up.uploaded == 0) {
            return null;
        }
        if (up.uploaded < up.frames.length) {
            return up.frames[0];
        }
        final long ticks = Minecraft.getSystemTime() / 50;
        return up.frames[(int) (ticks / up.source.frameTimeTicks % up.frames.length)];
    }

    /** Frees every uploaded texture. Render thread only. */
    public static void clear() {
        for (Uploaded up : UPLOADED.values()) {
            up.delete();
        }
        UPLOADED.clear();
    }

    /**
     * Frees textures not drawn for {@code maxAgeMs}, then the least recently drawn ones until at
     * most {@code maxCount} remain. Render thread only. Returns how many were freed.
     */
    public static int sweep(long maxAgeMs, int maxCount) {
        final long now = Minecraft.getSystemTime();
        final List<Map.Entry<String, Uploaded>> entries = new ArrayList<>(UPLOADED.entrySet());
        entries.sort((a, b) -> Long.compare(a.getValue().lastUsed, b.getValue().lastUsed));
        int freed = 0;
        int remaining = entries.size();
        for (Map.Entry<String, Uploaded> e : entries) {
            final boolean stale = now - e.getValue().lastUsed > maxAgeMs;
            if (!stale && remaining <= maxCount) {
                break; // Sorted oldest first: everything after is newer.
            }
            UPLOADED.remove(e.getKey());
            e.getValue().delete();
            remaining--;
            freed++;
        }
        return freed;
    }

    /** How many textures are on the GPU, counting every animation frame. */
    public static int uploadedFrames() {
        int n = 0;
        for (Uploaded up : UPLOADED.values()) {
            n += up.uploaded;
        }
        return n;
    }

    private static Decoded decode(String path, Kind kind) throws IOException {
        final BufferedImage image = readImage(path, AssetFiles.read(path));
        int frameW = image.getWidth();
        int frameH = image.getHeight();
        int frameTime = 1;
        boolean animated = false;
        if (AssetFiles.exists(path + ".mcmeta")) {
            try {
                final JsonObject anim = new JsonParser()
                        .parse(new String(AssetFiles.read(path + ".mcmeta"), StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("animation");
                if (anim != null) {
                    animated = true;
                    frameTime = anim.has("frametime") ? anim.get("frametime").getAsInt() : 1;
                    frameW = anim.has("width") ? anim.get("width").getAsInt() : image.getWidth();
                    frameH = anim.has("height") ? anim.get("height").getAsInt() : frameW;
                }
            } catch (Exception ignored) {
                // No usable animation data: treat it as one frame.
            }
        }
        final List<BufferedImage> frames = new ArrayList<>();
        final int[] essentialFrame = !animated && EssentialFiles.handles(path)
                ? EssentialFiles.frameSize(path, image.getWidth(), image.getHeight()) : null;
        if (essentialFrame != null) {
            // Essential: frames down the left edge, each the model's texture size (see EssentialFiles).
            // Anything to the right (a particle sheet) isn't part of the model's texture.
            final int rows = image.getHeight() / essentialFrame[1];
            for (int r = 0; r < rows; r++) {
                frames.add(image.getSubimage(0, r * essentialFrame[1], essentialFrame[0], essentialFrame[1]));
            }
            frameTime = EssentialFiles.FRAME_TICKS;
        } else if (animated && frameW > 0 && frameH > 0 && frameW <= image.getWidth() && frameH <= image.getHeight()) {
            final int cols = image.getWidth() / frameW;
            final int rows = image.getHeight() / frameH;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    frames.add(image.getSubimage(c * frameW, r * frameH, frameW, frameH));
                }
            }
        } else {
            frames.add(image);
        }
        final BufferedImage[] out = new BufferedImage[frames.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = kind == Kind.CLOAK ? toCapeLayout(frames.get(i)) : copy(frames.get(i), frames.get(i).getWidth(), frames.get(i).getHeight());
        }
        return new Decoded(out, frameTime);
    }

    private static BufferedImage toCapeLayout(BufferedImage frame) {
        final int w = frame.getWidth();
        final int h = frame.getHeight();
        if (w == 2 * h) {
            return copy(frame, w, h); // Already a full 64x32-style cape texture.
        }
        // Cropped to the cape model's 22x17 region: work out the scale and pad back out.
        final int scale = Math.max(1, Math.round(w / 22f));
        if (Math.abs(w - 22 * scale) > scale || Math.abs(h - 17 * scale) > scale) {
            return copy(frame, w, h);
        }
        final int target = Math.min(scale, MAX_CLOAK_SCALE);
        final BufferedImage canvas = new BufferedImage(64 * target, 32 * target, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(frame, 0, 0, 22 * target, 17 * target, null);
        g.dispose();
        return canvas;
    }

    private static BufferedImage copy(BufferedImage src, int w, int h) {
        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    static BufferedImage readImage(String path, byte[] data) throws IOException {
        if (path.endsWith(".webp")) {
            final ImageReader reader = new WebPImageReaderSpi().createReaderInstance(null);
            try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                reader.setInput(in);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        final BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) {
            throw new IOException("unreadable image " + path);
        }
        return image;
    }

}
