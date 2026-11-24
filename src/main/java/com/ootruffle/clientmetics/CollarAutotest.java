package com.ootruffle.clientmetics;

import com.ootruffle.clientmetics.cosmetic.Recolours;
import com.ootruffle.clientmetics.render.BellCollar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

/**
 * Dev-only check of the bell collar ({@code CLIENTMETICS_AUTOTEST=collar}): grants it in memory,
 * screenshots it hanging still, walks, turns and jumps to swing it (counting the jingles), recolours
 * the strap, opens its settings in the wardrobe, then puts everything back and quits.
 */
final class CollarAutotest {

    private static int step;
    private static int ticks;
    private static float fov;
    private static int ringsBefore;

    private CollarAutotest() {}

    static void tick() {
        final Minecraft mc = Minecraft.getMinecraft();
        final Config config = Config.get();
        ticks++;
        // In memory only (never saved: nothing here calls Config.save()).
        config.termsNoticeSeen = true;
        config.grantedExtras = java.util.Collections.singletonList(Recolours.BELL_COLLAR);
        mc.gameSettings.pauseOnLostFocus = false;
        if (step == 0) {
            if (mc.currentScreen instanceof GuiMainMenu && mc.theWorld == null) {
                config.bellCollar = true;
                mc.launchIntegratedServer("clientmetics-collar", "clientmetics-collar",
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
        final int forward = mc.gameSettings.keyBindForward.getKeyCode();
        switch (step) {
            case 1: // Hanging still, close up.
                if (ticks == 20) {
                    useTestSkin(mc);
                }
                if (ticks == 80) {
                    mc.gameSettings.hideGUI = true;
                    mc.gameSettings.thirdPersonView = 2;
                    fov = mc.gameSettings.fovSetting;
                    mc.gameSettings.fovSetting = 30;
                    mc.thePlayer.rotationPitch = 15;
                }
                if (ticks == 140) {
                    DevAutotest.screenshot(mc, "clientmetics-collar-still.png");
                    config.bellCollar = false; // The skin's own painted collar, to compare.
                }
                if (ticks == 160) {
                    DevAutotest.screenshot(mc, "clientmetics-collar-off.png");
                    config.bellCollar = true;
                    mc.gameSettings.thirdPersonView = 1; // From behind: the clasp.
                }
                if (ticks == 200) {
                    DevAutotest.screenshot(mc, "clientmetics-collar-back.png");
                    mc.gameSettings.thirdPersonView = 2;
                    ringsBefore = BellCollar.rings;
                    next();
                }
                break;
            case 2: // Walk, stop, turn and jump for a few seconds.
                KeyBinding.setKeyBindState(forward, ticks % 40 < 25);
                if (ticks % 40 == 30) {
                    mc.thePlayer.jump();
                }
                if (ticks % 40 > 25) {
                    mc.thePlayer.rotationYaw += 12;
                }
                if (ticks == 107) {
                    DevAutotest.screenshot(mc, "clientmetics-collar-swinging.png");
                }
                if (ticks > 160) {
                    KeyBinding.setKeyBindState(forward, false);
                    Clientmetics.LOGGER.info("[autotest] bell rang {} times while moving (0 while standing: {})",
                            BellCollar.rings - ringsBefore, ringsBefore == 0);
                    Recolours.setOwn(Recolours.BELL_COLLAR_KEY, 0x3B6BFF);
                    next();
                }
                break;
            case 3:
                if (ticks > 100) {
                    DevAutotest.screenshot(mc, "clientmetics-collar-blue.png");
                    mc.gameSettings.hideGUI = false;
                    mc.gameSettings.thirdPersonView = 0;
                    mc.gameSettings.fovSetting = fov;
                    mc.displayGuiScreen(Clientmetics.wardrobeScreen());
                    next();
                }
                break;
            case 4:
                if (ticks == 60 && mc.currentScreen instanceof com.ootruffle.clientmetics.render.WardrobeDev) {
                    ((com.ootruffle.clientmetics.render.WardrobeDev) mc.currentScreen).devSelect(Recolours.BELL_COLLAR_KEY);
                }
                if (ticks > 140) {
                    DevAutotest.screenshot(mc, "clientmetics-collar-wardrobe.png");
                    Recolours.setOwn(Recolours.BELL_COLLAR_KEY, null);
                    Clientmetics.LOGGER.info("[autotest] done, quitting");
                    mc.shutdown();
                    next();
                }
                break;
            default:
                break;
        }
    }

    /**
     * {@code CLIENTMETICS_TEST_SKIN=<png>}: draws you with that skin (classic arms), to see how the
     * collar sits on a real skin rather than Steve.
     */
    private static void useTestSkin(Minecraft mc) {
        final String file = System.getenv("CLIENTMETICS_TEST_SKIN");
        if (file == null || file.isEmpty()) {
            return;
        }
        try {
            final java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.File(file));
            final net.minecraft.util.ResourceLocation location = new net.minecraft.util.ResourceLocation("clientmetics", "dev/test_skin");
            mc.getTextureManager().loadTexture(location, new net.minecraft.client.renderer.texture.DynamicTexture(image));
            final net.minecraft.client.network.NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
            final Class<?> c = net.minecraft.client.network.NetworkPlayerInfo.class;
            final java.lang.reflect.Field loaded = c.getDeclaredField("playerTexturesLoaded");
            final java.lang.reflect.Field skin = c.getDeclaredField("locationSkin");
            final java.lang.reflect.Field type = c.getDeclaredField("skinType");
            loaded.setAccessible(true);
            skin.setAccessible(true);
            type.setAccessible(true);
            loaded.setBoolean(info, true);
            skin.set(info, location);
            type.set(info, "default");
            Clientmetics.LOGGER.info("[autotest] wearing test skin {}", file);
        } catch (Exception e) {
            Clientmetics.LOGGER.warn("[autotest] couldn't use the test skin: {}", e.toString());
        }
    }

    private static void next() {
        step++;
        ticks = 0;
    }

}
