package com.ootruffle.clientmetics;

import com.google.gson.JsonArray;
import com.ootruffle.clientmetics.asset.EssentialCatalog;
import com.ootruffle.clientmetics.cosmetic.EssentialCosmeticManager;
import com.ootruffle.clientmetics.cosmetic.EssentialSnapshot;
import com.ootruffle.clientmetics.cosmetic.Recolours;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

/**
 * Dev-only check of cosmetic colours on an Essential cosmetic ({@code CLIENTMETICS_AUTOTEST=essential-colour}):
 * wears Essential's cat ears in a test world and screenshots them, zoomed in, in their own colours
 * and then each of {@link #COLOURS}, then clears the colour and quits. Needs Essential's catalogue
 * in {@code run/config/clientmetics/essential}; nothing is sent anywhere.
 */
final class EssentialRecolourAutotest {

    private static final String[] KEYS = {"essential:CAT_EARS", "essential:HOODIE"};
    private static final int[] COLOURS = {-1, 0x3B6BFF, 0xFF3BC4, 0x3BD94A};
    private static final String[] NAMES = {"original", "blue", "pink", "green"};

    private static int step;
    private static int ticks;
    private static float fov;

    private EssentialRecolourAutotest() {}

    static void tick() {
        final Minecraft mc = Minecraft.getMinecraft();
        final Config config = Config.get();
        ticks++;
        // In memory only.
        config.termsNoticeSeen = true;
        config.essential = true;
        mc.gameSettings.pauseOnLostFocus = false;
        if (step == 0) {
            if (ticks == 1) {
                com.ootruffle.clientmetics.asset.Assets.EXECUTOR.execute(EssentialCatalog::load);
            }
            if (EssentialCatalog.get() == null) {
                if (ticks == 400) {
                    Clientmetics.LOGGER.error("[autotest] FAIL no Essential catalogue in {}", Config.dir().resolve("essential"));
                    mc.shutdown();
                }
                return;
            }
            if (mc.currentScreen instanceof GuiMainMenu && mc.theWorld == null) {
                mc.launchIntegratedServer("clientmetics-essential", "clientmetics-essential",
                        new WorldSettings(1234L, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT));
                next();
            }
            return;
        }
        if (mc.thePlayer == null) {
            return;
        }
        if (mc.getIntegratedServer() != null && mc.getIntegratedServer().worldServers != null) {
            for (net.minecraft.world.WorldServer world : mc.getIntegratedServer().worldServers) {
                if (world != null) {
                    world.setWorldTime(6000);
                }
            }
        }
        final UUID self = mc.thePlayer.getUniqueID();
        if (step == 1) {
            if (ticks < 100) {
                return;
            }
            final Map<String, String> slots = new LinkedHashMap<>();
            slots.put("HAT", "CAT_EARS");
            slots.put("TOP", "HOODIE"); // Black by default; recoloured from its blue/red/pink/green textures.
            EssentialCosmeticManager.showOutfit(new EssentialSnapshot(self, slots, Collections.<String, JsonArray>emptyMap()));
            mc.gameSettings.hideGUI = true;
            mc.gameSettings.thirdPersonView = 2; // Facing the camera.
            fov = mc.gameSettings.fovSetting;
            mc.gameSettings.fovSetting = 45; // Head and hoodie.
            mc.thePlayer.rotationPitch = 10;
            mc.thePlayer.prevRotationPitch = 10;
            config.debug = true;
            Clientmetics.LOGGER.info("[autotest] wearing Essential cat ears");
            next();
            return;
        }
        final int shot = step - 2;
        if (shot < COLOURS.length) {
            if (ticks == 1) {
                for (String key : KEYS) {
                    Recolours.setOwn(key, COLOURS[shot] < 0 ? null : COLOURS[shot]);
                }
                Clientmetics.LOGGER.info("[autotest] colour {}: lookup {}", NAMES[shot],
                        Integer.toHexString(Recolours.colour(self, KEYS[1])));
            } else if (ticks > 200) { // Let the recoloured texture decode and upload.
                DevAutotest.screenshot(mc, "clientmetics-cat-ears-" + NAMES[shot] + ".png");
                next();
            }
            return;
        }
        for (String key : KEYS) {
            Recolours.setOwn(key, null);
        }
        mc.gameSettings.fovSetting = fov;
        mc.gameSettings.hideGUI = false;
        mc.gameSettings.thirdPersonView = 0;
        Clientmetics.LOGGER.info("[autotest] done, quitting");
        mc.shutdown();
        step = Integer.MAX_VALUE;
    }

    private static void next() {
        step++;
        ticks = 0;
    }

}
