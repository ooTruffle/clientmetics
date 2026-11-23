package com.ootruffle.clientmetics.compat;

import com.ootruffle.clientmetics.Clientmetics;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/**
 * Optional ClientTag integration, looked up by reflection so ClientTag is never required:
 * <ul>
 *     <li>Players ClientTag already knows aren't on Lunar are never subscribed.</li>
 *     <li>ClientTag users who only look like Lunar users (ClientTag logs them in) are skipped,
 *     the same way ClientTag hides their Lunar icon.</li>
 *     <li>Mojang session joins go through ClientTag's queue, so the two mods don't trip
 *     Mojang's per-account rate limit by logging in at the same moment.</li>
 * </ul>
 * Any lookup that fails (an older or newer ClientTag) turns that piece off and logs once.
 */
public final class ClientTagCompat {

    private static volatile MethodHandle consentAccepted;
    private static Field consentDeclined;
    private static volatile MethodHandle getLunarColor;
    private static volatile MethodHandle hasAnswered;
    private static volatile MethodHandle hides;
    private static Object lunarIcon;
    private static MethodHandle join;
    private static boolean present;

    private ClientTagCompat() {}

    public static void init() {
        if (!FabricLoader.getInstance().isModLoaded("clienttag")) {
            return;
        }
        present = true;
        final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            final Class<?> tags = Class.forName("com.ootruffle.clienttag.LunarTagManager");
            getLunarColor = lookup.findStatic(tags, "getLunarColor", MethodType.methodType(Integer.class, UUID.class));
            hasAnswered = lookup.findStatic(tags, "hasAnswered", MethodType.methodType(boolean.class, UUID.class));
        } catch (Throwable t) {
            Clientmetics.LOGGER.warn("ClientTag found, but its Lunar lookups aren't usable: {}", t.toString());
        }
        try {
            final Class<?> icon = Class.forName("com.ootruffle.clienttag.render.ClientIcon");
            final Class<?> users = Class.forName("com.ootruffle.clienttag.ClientTagUsers");
            hides = lookup.findStatic(users, "hides", MethodType.methodType(boolean.class, UUID.class, icon));
            for (Object constant : icon.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals("LUNAR")) {
                    lunarIcon = constant;
                }
            }
        } catch (Throwable t) {
            Clientmetics.LOGGER.warn("ClientTag found, but its user list isn't usable: {}", t.toString());
        }
        try {
            final Class<?> joins = Class.forName("com.ootruffle.clienttag.platform.SessionJoins");
            join = lookup.findStatic(joins, "join", MethodType.methodType(void.class, String.class));
        } catch (Throwable t) {
            Clientmetics.LOGGER.warn("ClientTag found, but its login queue isn't usable: {}", t.toString());
        }
        try {
            final Class<?> consent = Class.forName("com.ootruffle.clienttag.Consent");
            consentAccepted = lookup.findStatic(consent, "accepted", MethodType.methodType(boolean.class));
            try {
                final Field declined = consent.getDeclaredField("declined");
                declined.setAccessible(true);
                consentDeclined = declined;
            } catch (Throwable t) {
                // Without it, ClientTag's warning counts as answered once it's off the screen for a while.
            }
        } catch (Throwable t) {
            Clientmetics.LOGGER.warn("ClientTag found, but its first-launch warning isn't readable: {}", t.toString());
        }
        Clientmetics.LOGGER.info("ClientTag detected; sharing its Lunar user list and login queue");
    }

    public static boolean isPresent() {
        return present;
    }

    /**
     * Whether ClientTag's first-launch warning is still waiting for an answer (not accepted, and
     * not declined this launch). Ours is held back until it isn't. Client thread only.
     */
    public static boolean consentPending() {
        final MethodHandle accepted = consentAccepted;
        if (accepted == null) {
            return false;
        }
        try {
            if ((boolean) accepted.invoke()) {
                return false;
            }
            return consentDeclined == null || !consentDeclined.getBoolean(null);
        } catch (Throwable t) {
            Clientmetics.LOGGER.warn("ClientTag's first-launch warning isn't readable, not waiting for it: {}", t.toString());
            consentAccepted = null;
            return false;
        }
    }

    /** Whether ClientTag's first-launch warning is on screen right now. */
    public static boolean isAsking() {
        final GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        return screen != null && screen.getClass().getName().startsWith("com.ootruffle.clienttag.");
    }

    /**
     * Whether ClientTag says this player shouldn't get Lunar cosmetics: it has already asked Lunar
     * and they're not on it, or they're a ClientTag user who isn't really running Lunar.
     */
    public static boolean knownNotOnLunar(UUID uuid) {
        try {
            final MethodHandle hidesHandle = hides;
            if (hidesHandle != null && lunarIcon != null && (boolean) hidesHandle.invoke(uuid, lunarIcon)) {
                return true;
            }
            final MethodHandle answered = hasAnswered;
            final MethodHandle color = getLunarColor;
            return answered != null && color != null && (boolean) answered.invoke(uuid) && color.invoke(uuid) == null;
        } catch (Throwable t) {
            Clientmetics.LOGGER.warn("ClientTag lookup failed, not using it any more: {}", t.toString());
            hides = null;
            hasAnswered = null;
            return false;
        }
    }

    /** Joins through ClientTag's spaced-out queue; false if it isn't available (the caller joins itself). */
    public static boolean join(String serverId) throws Exception {
        if (join == null) {
            return false;
        }
        try {
            join.invoke(serverId);
            return true;
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new Exception(t);
        }
    }

}
