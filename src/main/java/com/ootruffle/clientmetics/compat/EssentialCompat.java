package com.ootruffle.clientmetics.compat;

import com.ootruffle.clientmetics.Clientmetics;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Detects the Essential Mod.
 *
 * <p>Essential and this mod cannot be installed together in practice: Essential ships 1.8.9 for
 * Legacy Forge, and this mod runs on Ornithe. When an Essential build is nevertheless present,
 * Essential owns its own sign-in, backend and renderer, so this mod's Essential provider stands
 * down ({@link #installed()}) rather than drawing the same cosmetics twice.
 *
 * <p>When Essential is absent, the provider talks to Essential's connection manager itself
 * ({@code cosmetic.EssentialCosmeticManager}). That path is unofficial; see {@code docs/essential.md}.
 */
public final class EssentialCompat {

    private static final String[] MOD_IDS = {
        "essential",
        "essential-mod",
        "essential-container",
        "ggessential"
    };

    private static ModContainer container;

    private EssentialCompat() {}

    public static void init() {
        container = findEssential();
        if (container == null) {
            return;
        }
        Clientmetics.LOGGER.info(
            "Essential detected ({} {}); leaving Essential cosmetics to Essential",
            container.getMetadata().getId(),
            container.getMetadata().getVersion().getFriendlyString()
        );
    }

    /** Whether Essential is loaded in this game, in which case this mod's provider stays out of the way. */
    public static boolean installed() {
        return container != null;
    }

    public static boolean isPresent() {
        return installed();
    }

    /** Essential's loaded mod ID, or null if it isn't loaded. */
    public static String modId() {
        return container == null ? null : container.getMetadata().getId();
    }

    private static ModContainer findEssential() {
        for (String id : MOD_IDS) {
            final Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(id);
            if (mod.isPresent()) {
                return mod.get();
            }
        }
        return null;
    }
}
