package com.ootruffle.clientmetics;

import com.ootruffle.clientmetics.asset.MemoryJanitor;
import com.ootruffle.clientmetics.asset.Textures;
import com.ootruffle.clientmetics.asset.VeloFiles;
import com.ootruffle.clientmetics.cosmetic.VeloCosmeticManager;
import com.ootruffle.clientmetics.net.VeloApi;
import com.ootruffle.clientmetics.render.Cloaks;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

/**
 * Dev-only check of Velo capes ({@code CLIENTMETICS_AUTOTEST=velo}). In a flat world it wears, in turn:
 * <ol>
 *     <li>your own cape from a local Velo folder ({@code run/velo-test}, a generated static
 *     {@code .velocape} with a different colour on each face, so a wrong layout shows);</li>
 *     <li>two Velo Store capes, looked up and downloaded from Velo's API as for any other player
 *     (animated GIFs; one is 2048 wide, so it's sampled down);</li>
 * </ol>
 * screenshotting each from behind, in front and while walking, and checking the animation moves on.
 * Then it frees every texture and checks the cape loads again, reads Velo's live online list once,
 * and quits. Each check logs {@code [autotest] PASS} or {@code FAIL}.
 */
final class VeloAutotest {

    private static final String[] STORE = {"cape_velo_staff_green", "cape_obsidian"};
    private static final int LOAD_TIMEOUT_TICKS = 1200;

    private static int step;
    private static int ticks;
    private static int capeIndex;
    private static int pass;
    private static int fail;
    private static String path;
    private static int readyAt = -1;
    private static final Set<ResourceLocation> seenFrames = new HashSet<>();
    private static volatile String onlineResult;

    private VeloAutotest() {}

    static void tick() {
        final Minecraft mc = Minecraft.getMinecraft();
        final Config config = Config.get();
        ticks++;
        // In memory only (nothing here saves the config).
        config.termsNoticeSeen = true;
        config.velo = true;
        config.showCloaks = true;
        config.showOwn = true;
        mc.gameSettings.pauseOnLostFocus = false;
        if (step == 0) {
            if (mc.currentScreen instanceof GuiMainMenu && mc.theWorld == null) {
                writeLocalBundle(mc);
                mc.launchIntegratedServer("clientmetics-velo", "clientmetics-velo",
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
        final int forward = mc.gameSettings.keyBindForward.getKeyCode();
        switch (step) {
            case 1: // Your own cape, from the local Velo folder.
                if (ticks == 60) {
                    mc.gameSettings.hideGUI = true;
                    mc.gameSettings.fovSetting = 50;
                    mc.thePlayer.rotationPitch = 10;
                    path = VeloFiles.localCapePath(self);
                    check(path != null, "local equipped cape found: " + path);
                    next();
                    step = 10;
                    capeIndex = -1;
                }
                break;
            case 10: // Wait for the current cape to load; then screenshot it.
                if (ticks == 1) {
                    readyAt = -1;
                    seenFrames.clear();
                    if (capeIndex >= 0) {
                        path = VeloFiles.capePath(STORE[capeIndex]);
                        VeloCosmeticManager.showCape(self, path);
                    }
                    Clientmetics.LOGGER.info("[autotest] wearing Velo cape {}", path);
                    mc.gameSettings.thirdPersonView = 1;
                    mc.thePlayer.rotationYaw = 0;
                }
                final String shown = VeloCosmeticManager.cape(self);
                final ResourceLocation cape = Cloaks.capeFor(self);
                if (cape != null && readyAt < 0) {
                    readyAt = ticks;
                    check(path.equals(shown), "cape " + name() + " is the one worn (" + shown + ")");
                    check(cape.equals(mc.thePlayer.getLocationCape()), "cape " + name() + " replaces the player's cape texture");
                    Clientmetics.LOGGER.info("[autotest] {} loaded after {} ticks", name(), ticks);
                }
                if (readyAt >= 0) {
                    seenFrames.add(cape);
                    final int t = ticks - readyAt;
                    if (t == 40) {
                        DevAutotest.screenshot(mc, "clientmetics-velo-" + name() + "-back.png");
                        mc.gameSettings.thirdPersonView = 2;
                    } else if (t == 70) {
                        DevAutotest.screenshot(mc, "clientmetics-velo-" + name() + "-front.png");
                        mc.gameSettings.thirdPersonView = 1;
                        mc.thePlayer.rotationYaw = 30;
                    } else if (t > 80 && t < 130) {
                        KeyBinding.setKeyBindState(forward, true);
                        if (t == 125) {
                            DevAutotest.screenshot(mc, "clientmetics-velo-" + name() + "-walking.png");
                        }
                    } else if (t == 130) {
                        KeyBinding.setKeyBindState(forward, false);
                        if (capeIndex >= 0) {
                            check(seenFrames.size() > 1, "cape " + name() + " animates (" + seenFrames.size() + " frames seen)");
                        } else {
                            check(seenFrames.size() == 1, "static cape " + name() + " stays on one frame");
                        }
                        capeIndex++;
                        if (capeIndex < STORE.length) {
                            ticks = 0;
                        } else {
                            next();
                            step = 20;
                        }
                    }
                } else if (ticks > LOAD_TIMEOUT_TICKS) {
                    check(false, "cape " + name() + " loaded (gave up after " + ticks + " ticks; see the log)");
                    capeIndex++;
                    ticks = 0;
                    if (capeIndex >= STORE.length) {
                        next();
                        step = 20;
                    }
                }
                break;
            case 20: // Free every texture; the cape must come back.
                if (ticks == 1) {
                    final int before = Textures.uploadedFrames();
                    MemoryJanitor.sweepAll();
                    Clientmetics.LOGGER.info("[autotest] freed Velo textures: {} frames before, {} after", before, Textures.uploadedFrames());
                }
                if (Cloaks.capeFor(self) != null) {
                    check(true, "cape loads again after freeing memory (" + ticks + " ticks)");
                    DevAutotest.screenshot(mc, "clientmetics-velo-reloaded.png");
                    next();
                    step = 30;
                } else if (ticks > LOAD_TIMEOUT_TICKS) {
                    check(false, "cape loads again after freeing memory");
                    next();
                    step = 30;
                }
                break;
            case 30: // Velo's live online list, as the poller reads it.
                if (ticks == 1) {
                    new Thread(() -> {
                        try {
                            final Map<UUID, String> online = VeloApi.online();
                            int capes = 0;
                            for (String id : online.values()) {
                                if (VeloFiles.capePath(id) != null) {
                                    capes++;
                                }
                            }
                            onlineResult = "ok: " + online.size() + " users, " + capes + " capes";
                        } catch (Exception e) {
                            onlineResult = "failed: " + e;
                        }
                    }, "Clientmetics-VeloAutotest").start();
                }
                if (onlineResult != null) {
                    check(onlineResult.startsWith("ok"), "Velo online list " + onlineResult);
                    VeloCosmeticManager.showCape(self, null);
                    mc.gameSettings.hideGUI = false;
                    mc.gameSettings.thirdPersonView = 0;
                    Clientmetics.LOGGER.info("[autotest] Velo: {} passed, {} failed", pass, fail);
                    Clientmetics.LOGGER.info("[autotest] done, quitting");
                    mc.shutdown();
                    next();
                }
                break;
            default:
                break;
        }
    }

    private static String name() {
        return capeIndex < 0 ? "local" : STORE[capeIndex];
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        Clientmetics.LOGGER.info("[autotest] {} {}", ok ? "PASS" : "FAIL", what);
    }

    /**
     * A local Velo folder with one static cape equipped: a 64x32 texture with a different colour on
     * each face of the cape (front red, back blue, sides green, top white, bottom yellow) and an
     * arrow pointing up on the front, so a wrong crop, flip or offset is easy to spot.
     */
    private static void writeLocalBundle(Minecraft mc) {
        try {
            final Path home = mc.mcDataDir.toPath().resolve("velo-test");
            System.setProperty("velo.home", home.toAbsolutePath().toString());
            final BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g = image.createGraphics();
            fill(g, Color.WHITE, 1, 0, 10, 1);     // top
            fill(g, Color.YELLOW, 11, 0, 10, 1);   // bottom
            fill(g, Color.GREEN, 0, 1, 1, 16);     // right side
            fill(g, Color.RED, 1, 1, 10, 16);      // front (outside)
            fill(g, Color.GREEN, 11, 1, 1, 16);    // left side
            fill(g, Color.BLUE, 12, 1, 10, 16);    // back (inside)
            g.setColor(Color.WHITE);               // an up arrow on the front
            g.fillRect(5, 5, 2, 9);
            g.fillRect(4, 6, 4, 1);
            g.fillRect(3, 7, 6, 1);
            fill(g, new Color(255, 0, 255), 30, 0, 34, 32); // outside the cape area: must never show
            g.dispose();
            final ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            Files.createDirectories(home.resolve("capes"));
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(home.resolve("capes/autotest.velocape")))) {
                zip.putNextEntry(new ZipEntry("meta.json"));
                zip.write("{\"id\":\"autotest\",\"name\":\"Autotest\"}".getBytes(StandardCharsets.UTF_8));
                zip.putNextEntry(new ZipEntry("texture.png"));
                zip.write(png.toByteArray());
            }
            Files.createDirectories(home.resolve("config"));
            Files.write(home.resolve("config/cosmetics-cape.json"), "{\"equippedCapeId\":\"autotest\"}".getBytes(StandardCharsets.UTF_8));
            Clientmetics.LOGGER.info("[autotest] local Velo folder at {}", home);
        } catch (Exception e) {
            Clientmetics.LOGGER.warn("[autotest] couldn't write the local Velo folder: {}", e.toString());
        }
    }

    private static void fill(Graphics2D g, Color colour, int x, int y, int w, int h) {
        g.setColor(colour);
        g.fillRect(x, y, w, h);
    }

    private static void next() {
        step++;
        ticks = 0;
    }

}
