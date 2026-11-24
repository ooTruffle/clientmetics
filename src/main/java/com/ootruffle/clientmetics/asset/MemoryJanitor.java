package com.ootruffle.clientmetics.asset;

import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.model.GeoCosmetic;
import com.ootruffle.clientmetics.model.ObjModel;

/**
 * Frees cosmetics nobody near you has worn for a while, so memory doesn't keep growing on busy
 * servers where hundreds of outfits go past. Runs on the client (render) thread, since freeing
 * textures and display lists needs the GL context. Anything freed loads again the next time it's
 * worn (from the local cache, so no new download).
 */
public final class MemoryJanitor {

    private static final int INTERVAL_TICKS = 600; // 30 s
    private static final long TEXTURE_MAX_AGE_MS = 3 * 60_000;
    private static final long MODEL_MAX_AGE_MS = 5 * 60_000;
    /** Decoded images waiting to be drawn (preloaded for players who never came into view). */
    private static final long DECODED_MAX_AGE_MS = 3 * 60_000;
    /** At most this many textures on the GPU (each animation frame counts), oldest freed first. */
    private static final int MAX_TEXTURES = 1500;

    private static int ticks;

    private MemoryJanitor() {}

    /** Called every client tick. */
    public static void onClientTick() {
        if (++ticks < INTERVAL_TICKS) {
            return;
        }
        ticks = 0;
        sweep(TEXTURE_MAX_AGE_MS, DECODED_MAX_AGE_MS, MODEL_MAX_AGE_MS);
    }

    /** Frees everything, as if nothing had been worn for a long time (dev autotest). Render thread only. */
    public static void sweepAll() {
        sweep(-1, -1, -1);
    }

    private static void sweep(long textureAge, long decodedAge, long modelAge) {
        final int textures = Textures.sweep(textureAge, textureAge < 0 ? 0 : MAX_TEXTURES);
        final int decoded = Assets.sweep("texture-" + Textures.Kind.MODEL, decodedAge, null)
                + Assets.sweep("texture-" + Textures.Kind.CLOAK, decodedAge, null)
                // Recoloured copies (see Recolour) are kinds of their own: texture-MODEL#rrggbb.
                + Assets.sweepPrefix("texture-" + Textures.Kind.MODEL + "#", decodedAge, null)
                + Assets.sweepPrefix("texture-" + Textures.Kind.CLOAK + "#", decodedAge, null);
        final int models = Assets.sweep("obj", modelAge, v -> ((ObjModel) v).delete())
                + Assets.sweep("geo", modelAge, v -> ((GeoCosmetic) v).delete());
        if (Config.get().debug && textures + decoded + models > 0) {
            Clientmetics.LOGGER.info("Freed {} textures, {} decoded images and {} models; {} texture frames and {} models left",
                    textures, decoded, models, Textures.uploadedFrames(), Assets.count("obj") + Assets.count("geo"));
        }
    }

}
