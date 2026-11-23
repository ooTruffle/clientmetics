package com.ootruffle.clientmetics.compat;

import com.ootruffle.clientmetics.Clientmetics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Which cosmetics clients the game itself is running in (or alongside). Each one already draws its
 * own cosmetics, so the matching source here stands down instead of drawing them twice, and its
 * wardrobe tab goes away (use that client's own wardrobe). With none of them, every source runs.
 * <ul>
 *     <li><b>Lunar Client:</b> its launcher starts the game with {@code ichor.*} system properties,
 *     and its classes are under {@code com.moonsworth.lunar}.</li>
 *     <li><b>Dawn Client</b> (formerly Feather): a Fabric mod with the ID {@code dawn} (or
 *     {@code feather}), with its classes under {@code net.digitalingot.feather}.</li>
 *     <li><b>Essential:</b> see {@link EssentialCompat}.</li>
 * </ul>
 * Classes are looked up as resources, never loaded, so nothing of theirs is initialised (or
 * transformed) early by this check.
 * <p>
 * {@code -Dclientmetics.hostClient=lunar,dawn,essential} (any of them) pretends those are present,
 * for testing.
 */
public final class HostClients {

    private static final List<String> PRETEND = pretend();

    private static final boolean LUNAR = PRETEND.contains("lunar") || detectLunar();
    private static final boolean DAWN = PRETEND.contains("dawn") || detectDawn();

    private HostClients() {}

    /** Logs what was found. Called once at startup, after {@link EssentialCompat#init()}. */
    public static void init() {
        final List<String> found = new ArrayList<>();
        if (LUNAR) {
            found.add("Lunar Client");
        }
        if (DAWN) {
            found.add("Dawn Client");
        }
        if (essential()) {
            found.add("Essential");
        }
        if (found.isEmpty()) {
            Clientmetics.LOGGER.info("No other cosmetics client detected; showing Lunar, Dawn and Essential cosmetics");
        } else {
            Clientmetics.LOGGER.info("Running with {}; leaving their own cosmetics to them", String.join(", ", found));
        }
    }

    /** Running in Lunar Client, which draws Lunar cosmetics itself. */
    public static boolean lunar() {
        return LUNAR;
    }

    /** Running with Dawn Client, which draws Dawn cosmetics itself. */
    public static boolean dawn() {
        return DAWN;
    }

    /** Essential is installed, and draws Essential cosmetics itself. */
    public static boolean essential() {
        return PRETEND.contains("essential") || EssentialCompat.installed();
    }

    private static boolean detectLunar() {
        for (Object key : System.getProperties().keySet()) {
            if (String.valueOf(key).startsWith("ichor.")) {
                return true;
            }
        }
        return hasResource("com/moonsworth/lunar/");
    }

    private static boolean detectDawn() {
        final FabricLoader loader = FabricLoader.getInstance();
        return loader.isModLoaded("dawn") || loader.isModLoaded("feather")
                || hasResource("net/digitalingot/feather/FeatherMod.class");
    }

    private static boolean hasResource(String path) {
        for (ClassLoader loader : new ClassLoader[]{HostClients.class.getClassLoader(), ClassLoader.getSystemClassLoader()}) {
            try {
                if (loader != null && loader.getResource(path) != null) {
                    return true;
                }
            } catch (Throwable ignored) {
                // A loader that can't answer doesn't have it.
            }
        }
        return false;
    }

    private static List<String> pretend() {
        final List<String> out = new ArrayList<>();
        final String value = System.getProperty("clientmetics.hostClient");
        if (value != null) {
            for (String part : value.split(",")) {
                out.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

}
