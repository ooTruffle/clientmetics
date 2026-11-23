package com.ootruffle.clientmetics.compat;

import com.ootruffle.clientmetics.Clientmetics;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Optional OneConfig support. Without OneConfig, settings come only from {@code config.json}.
 * With it, {@link ClientmeticsPage} adds a settings page and {@code /clientmetics}, and its values
 * are copied into {@link com.ootruffle.clientmetics.Config} once a second.
 * <p>
 * The wardrobe (drawn with PolyCompose) doesn't need OneConfig; with OneConfig it
 * also links to this page and OneConfig's menu, and follows OneConfig's theme.
 * <p>
 * This class never touches OneConfig's classes itself, so it's safe to load without OneConfig.
 */
public final class OneConfigCompat {

    // "oneconfig" from 1.9 on (the versions that run on PolyCompose); "oneconfigv1" before that.
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("oneconfig")
            || FabricLoader.getInstance().isModLoaded("oneconfigv1");
    private static final int SYNC_INTERVAL_TICKS = 20;

    private static boolean active;
    private static int ticks;

    private OneConfigCompat() {}

    public static void init() {
        if (!LOADED) {
            return;
        }
        try {
            ClientmeticsPage.init();
            active = true;
            Clientmetics.LOGGER.info("OneConfig detected; settings are on its Clientmetics page and /clientmetics");
        } catch (Throwable t) {
            Clientmetics.LOGGER.warn("OneConfig found, but its settings page couldn't be set up (Clientmetics needs OneConfig 1.9 or newer): {}",
                    t.toString());
        }
    }

    /** Whether OneConfig is installed and its settings page is set up. */
    public static boolean isActive() {
        return active;
    }

    /** Opens the Clientmetics page in OneConfig. Returns false (doing nothing) without OneConfig. */
    public static boolean openSettings() {
        if (!active) {
            return false;
        }
        try {
            ClientmeticsPage.openSettings();
            return true;
        } catch (Throwable t) {
            Clientmetics.LOGGER.warn("Couldn't open the OneConfig settings page: {}", t.toString());
            return false;
        }
    }

    /** Opens OneConfig's mod list. Returns false (doing nothing) without OneConfig. */
    public static boolean openMods() {
        if (!active) {
            return false;
        }
        try {
            ClientmeticsPage.openMods();
            return true;
        } catch (Throwable t) {
            Clientmetics.LOGGER.warn("Couldn't open OneConfig: {}", t.toString());
            return false;
        }
    }

    /** Called every client tick. */
    public static void onClientTick() {
        if (active && ++ticks >= SYNC_INTERVAL_TICKS) {
            ticks = 0;
            ClientmeticsPage.sync();
        }
    }

}
