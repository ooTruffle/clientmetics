package com.ootruffle.clientmetics;

import com.ootruffle.clientmetics.update.Updater;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;

/**
 * Dev-only check of the updater ({@code CLIENTMETICS_AUTOTEST=updater}). The updater pretends to be
 * 1.0.0 installed as a fake jar in {@code run/updater-test/mods}, so GitHub's real latest release is
 * offered. This screenshots the offer, starts the download as if "Update" were clicked, screenshots
 * the result and quits, so the installer swaps the fake jar for the release once the game has closed
 * (check {@code run/updater-test/mods} and {@code run/config/clientmetics/update/update.log}).
 */
final class UpdaterAutotest {

    private static final int TIMEOUT_TICKS = 1200;

    private static int step;
    private static int ticks;

    private UpdaterAutotest() {}

    static void tick() {
        final Minecraft mc = Minecraft.getMinecraft();
        ticks++;
        Config.get().termsNoticeSeen = true; // In memory only.
        Config.get().checkForUpdates = true;
        mc.gameSettings.pauseOnLostFocus = false;
        if (!(mc.currentScreen instanceof GuiMainMenu) && step < 3) {
            return;
        }
        switch (step) {
            case 0:
                if (Updater.availableVersion() != null) {
                    Clientmetics.LOGGER.info("[autotest] PASS update offered: {} ({})", Updater.availableVersion(), Updater.status());
                    next();
                } else if (ticks > TIMEOUT_TICKS) {
                    Clientmetics.LOGGER.info("[autotest] FAIL no update offered ({})", Updater.status());
                    step = 3;
                }
                break;
            case 1:
                if (ticks == 60) {
                    DevAutotest.screenshot(mc, "clientmetics-updater-offer.png");
                    Updater.devUpdate();
                    next();
                }
                break;
            case 2: {
                final String status = String.valueOf(Updater.status());
                if (status.contains("installed when")) {
                    if (ticks % 20 == 0 && ticks >= 40) {
                        Clientmetics.LOGGER.info("[autotest] PASS downloaded and verified: {}", status);
                        DevAutotest.screenshot(mc, "clientmetics-updater-ready.png");
                        next();
                    }
                } else if (status.startsWith("Update failed") || ticks > TIMEOUT_TICKS) {
                    Clientmetics.LOGGER.info("[autotest] FAIL download: {}", status);
                    DevAutotest.screenshot(mc, "clientmetics-updater-failed.png");
                    next();
                } else if (ticks == 10) {
                    DevAutotest.screenshot(mc, "clientmetics-updater-downloading.png");
                }
                break;
            }
            case 3:
                if (ticks > 40) {
                    Clientmetics.LOGGER.info("[autotest] done, quitting (the installer runs once the game has closed)");
                    mc.shutdown();
                    next();
                }
                break;
            default:
                break;
        }
    }

    private static void next() {
        step++;
        ticks = 0;
    }

}
