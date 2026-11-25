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

    static final class Decoded {
        final BufferedImage[] frames;
        final int frameTimeTicks;
        /** Each frame's own duration in milliseconds (animated GIFs), or null for {@link #frameTimeTicks}. */
        final int[] frameMs;
        final long totalMs;

        Decoded(BufferedImage[] frames, int frameTimeTicks) {
            this.frames = frames;
            this.frameTimeTicks = Math.max(1, frameTimeTicks);
            this.frameMs = null;
            this.totalMs = 0;
        }

        Decoded(BufferedImage[] frames, int[] frameMs) {
            this.frames = frames;
            this.frameTimeTicks = 1;
            this.frameMs = frames.length > 1 ? frameMs : null;
            long total = 0;
            if (this.frameMs != null) {
                for (int ms : frameMs) {
                    total += ms;
                }
            }
            this.totalMs = total;
        }

        int frameAt(long nowMs) {
            if (frameMs == null || totalMs <= 0) {
                return (int) (nowMs / 50 / frameTimeTicks % frames.length);
            }
            long t = nowMs % totalMs;
            for (int i = 0; i < frameMs.length; i++) {
                t -= frameMs[i];
                if (t < 0) {
                    return i;
                }
            }
            return frameMs.length - 1;
        }
    }

    private static final class Uploaded {
        final String assetKind;
        final String path;
        final Decoded source;
        final ResourceLocation[] frames;
        int uploaded;
        long lastUsed;

        Uploaded(String assetKind, String path, Decoded source) {
            this.assetKind = assetKind;
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
            Assets.forget(assetKind, path);
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
        preload(path, kind, -1);
    }

    /** Like {@link #preload(String, Kind)}, recoloured to {@code colour} (RGB; -1 for as-is). */
    public static void preload(String path, Kind kind, int colour) {
        Assets.get(assetKind(kind, colour), path, p -> decode(p, kind, colour));
    }

    /** The texture's current animation frame, or null if it isn't ready (or doesn't exist). Render thread only. */
    public static ResourceLocation get(String path, Kind kind) {
        return get(path, kind, -1);
    }

    /**
     * Like {@link #get(String, Kind)}, recoloured to {@code colour} (RGB; -1 for as-is; see
     * {@link Recolour}). While the recoloured copy is still being made, the normal one is drawn.
     */
    public static ResourceLocation get(String path, Kind kind, int colour) {
        if (colour >= 0) {
            final ResourceLocation recoloured = upload(path, kind, colour);
            return recoloured != null ? recoloured : upload(path, kind, -1);
        }
        return upload(path, kind, -1);
    }

    private static String assetKind(Kind kind, int colour) {
        return colour < 0 ? "texture-" + kind : "texture-" + kind + "#" + Integer.toHexString(colour & 0xFFFFFF);
    }

    private static ResourceLocation upload(String path, Kind kind, int colour) {
        final String assetKind = assetKind(kind, colour);
        final String key = assetKind + ":" + path;
        Uploaded up = UPLOADED.get(key);
        if (up == null) {
            final Decoded decoded = Assets.get(assetKind, path, p -> decode(p, kind, colour));
            if (decoded == null || decoded.frames.length == 0) {
                return null;
            }
            up = new Uploaded(assetKind, path, decoded);
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
        return up.frames[up.source.frameAt(Minecraft.getSystemTime())];
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

    private static Decoded decode(String path, Kind kind, int colour) throws IOException {
        if (colour >= 0 && kind == Kind.MODEL) {
            final Decoded fromVariants = decodeFromVariants(path, colour);
            if (fromVariants != null) {
                return fromVariants;
            }
        }
        if (VeloFiles.handles(path)) {
            // Velo capes: GIFs with their own frame times, laid out the way Velo samples them.
            final Decoded velo = VeloFiles.decode(path);
            if (colour >= 0) {
                Recolour.apply(velo.frames, colour);
            }
            return velo;
        }
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
        if (colour >= 0) {
            Recolour.apply(out, colour);
        }
        return new Decoded(out, frameTime);
    }

    /**
     * An Essential texture recoloured from the cosmetic's own colours (see {@link Recolour#fromVariants}),
     * or null to recolour it the usual way: it has fewer than two colours, is animated, or its
     * colours' textures aren't the same size.
     */
    private static Decoded decodeFromVariants(String path, int colour) throws IOException {
        final String[] sources = EssentialFiles.recolourSources(path);
        if (sources == null) {
            return null;
        }
        final Decoded worn = decode(path, Kind.MODEL, -1);
        if (worn.frames.length != 1) {
            return null;
        }
        final int w = worn.frames[0].getWidth();
        final int h = worn.frames[0].getHeight();
        final BufferedImage[] variants = new BufferedImage[sources.length - 1];
        for (int i = 1; i < sources.length; i++) {
            final BufferedImage image = readImage(sources[i], AssetFiles.read(sources[i]));
            if (image.getWidth() < w || image.getHeight() < h) {
                return null;
            }
            // Cropped like the worn texture (Essential textures can have a particle sheet to the right).
            variants[i - 1] = copy(image.getSubimage(0, 0, w, h), w, h);
        }
        final BufferedImage base = copy(variants[java.util.Arrays.asList(sources).subList(1, sources.length).indexOf(sources[0])], w, h);
        if (!Recolour.fromVariants(base, variants, colour)) {
            return null;
        }
        if (com.ootruffle.clientmetics.Config.get().debug) {
            com.ootruffle.clientmetics.Clientmetics.LOGGER.info("Recoloured {} from {} ({} colours)", path, sources[0], variants.length);
        }
        return new Decoded(new BufferedImage[]{base}, worn.frameTimeTicks);
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
