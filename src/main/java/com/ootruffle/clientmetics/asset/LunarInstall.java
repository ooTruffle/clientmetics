package com.ootruffle.clientmetics.asset;

import com.ootruffle.clientmetics.Config;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Where the user's own Lunar install keeps its files. Nothing of Lunar's is bundled with this mod:
 * the catalogue and models are read from here at runtime.
 */
public final class LunarInstall {

    private LunarInstall() {}

    public static Path root() {
        final String configured = Config.get().lunarDirectory;
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim());
        }
        return Paths.get(System.getProperty("user.home"), ".lunarclient");
    }

    /** {@code ~/.lunarclient/textures/assets/lunar}: the catalogue, indexes and a few eagerly shipped files. */
    public static Path textures() {
        return root().resolve("textures").resolve("assets").resolve("lunar");
    }

    /** {@code ~/.lunarclient/jit/assets/lunar-jit}: files the launcher already downloaded on demand. */
    public static Path jitCache() {
        return root().resolve("jit").resolve("assets").resolve("lunar-jit");
    }

    public static boolean exists() {
        return Files.isRegularFile(textures().resolve("cosmetics.json"));
    }

}
