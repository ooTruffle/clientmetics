package com.ootruffle.clientmetics;

import com.ootruffle.clientmetics.asset.AssetFiles;
import com.ootruffle.clientmetics.asset.Catalog;
import com.ootruffle.clientmetics.cosmetic.LunarAccount;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

/**
 * Dev-only visual check of the wardrobe, for the dev client (never runs in a real install: it
 * needs the dev environment and {@code CLIENTMETICS_AUTOTEST=1}). Opens a flat test world, loads
 * sample cosmetics that are cached locally, opens the wardrobe, takes screenshots and quits.
 */
public final class DevAutotest {

    private static final boolean DEV = FabricLoader.getInstance().isDevelopmentEnvironment();
    private static final boolean ENABLED = DEV && "1".equals(System.getenv("CLIENTMETICS_AUTOTEST"));
    /** {@code CLIENTMETICS_AUTOTEST=dawn}: wears sample Dawn cosmetics from a local Dawn install instead. */
    private static final boolean DAWN = DEV && "dawn".equals(System.getenv("CLIENTMETICS_AUTOTEST"));
    /**
     * {@code CLIENTMETICS_AUTOTEST=essential}: checks the Essential colour and cape rules, wears
     * sample Essential outfits and fills the Essential wardrobe with demo data. Needs Essential's
     * catalogue in {@code run/config/clientmetics/essential} (copied from any install that has
     * connected once); nothing is sent to Essential.
     */
    private static final boolean ESSENTIAL = DEV && "essential".equals(System.getenv("CLIENTMETICS_AUTOTEST"));
    /** {@code CLIENTMETICS_AUTOTEST=essential-colour}: Essential cat ears in several colours (see {@link EssentialRecolourAutotest}). */
    /** {@code CLIENTMETICS_AUTOTEST=collar}: the bell collar (see {@link CollarAutotest}). */
    private static final boolean COLLAR = DEV && "collar".equals(System.getenv("CLIENTMETICS_AUTOTEST"));
    private static final boolean ESSENTIAL_COLOUR = DEV && "essential-colour".equals(System.getenv("CLIENTMETICS_AUTOTEST"));
    private static final String[][] DAWN_OUTFITS = {
            {"hat", "wing", "pet", "boot", "cape"},
            {"face", "back", "arm", "aura"},
            {"hat", "wing", "pet", "back", "cape"},
    };
    private static final String[] CATEGORIES = {"cloak", "hat", "mask", "bandanna", "pet", "dragon_wings", "backpack", "bodywear", "suits", "shoes"};

    private static final String[] TABS = {"mask", "hat", "bandanna"};

    private static int step;
    private static int ticks;

    private DevAutotest() {}

    public static void onClientTick() {
        if (DAWN) {
            dawnTick();
        }
        if (ESSENTIAL) {
            EssentialAutotest.tick();
        }
        if (ESSENTIAL_COLOUR) {
            EssentialRecolourAutotest.tick();
        }
        if (COLLAR) {
            CollarAutotest.tick();
        }
        if (!ENABLED) {
            return;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        ticks++;
        Config.get().termsNoticeSeen = true; // In memory only: nothing loads until the warning is accepted.
        switch (step) {
            case 0:
                if (Catalog.get() != null && mc.currentScreen instanceof GuiMainMenu && mc.theWorld == null) {
                    Clientmetics.LOGGER.info("[autotest] opening a test world");
                    mc.launchIntegratedServer("clientmetics-test2", "clientmetics-test2",
                            new WorldSettings(1234L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT));
                    next();
                }
                break;
            case 1:
                if (mc.thePlayer != null && ticks > 60) {
                    final List<Integer> owned = sampleIds();
                    Clientmetics.LOGGER.info("[autotest] {} sample cosmetics", owned.size());
                    LunarAccount.loadDemo(owned);
                    final Catalog catalog = Catalog.get();
                    for (String category : new String[]{"cloak", "mask", "pet"}) {
                        for (int id : owned) {
                            if (category.equals(catalog.entry(id).category)) {
                                LunarAccount.toggle(id);
                                break;
                            }
                        }
                    }
                    mc.displayGuiScreen(Clientmetics.wardrobeScreen());
                    next();
                }
                break;
            case 2:
                if (ticks > 240) { // Let models and textures load.
                    screenshot(mc, "clientmetics-wardrobe-all.png");
                    next();
                }
                break;
            case 3: {
                // Settings: open the equipped mask's settings, turn on "Show over helmet", raise it two steps.
                final LunarAccount.State state = LunarAccount.state();
                final Catalog catalog = Catalog.get();
                Integer mask = null;
                for (com.ootruffle.clientmetics.cosmetic.EquippedCosmetic c : state.equipped) {
                    if ("mask".equals(catalog.entry(c.id).category)) {
                        mask = c.id;
                    }
                }
                if (mask != null && mc.currentScreen instanceof com.ootruffle.clientmetics.render.WardrobeDev) {
                    final Catalog.Entry entry = catalog.entry(mask);
                    ((com.ootruffle.clientmetics.render.WardrobeDev) mc.currentScreen).devSelectEquipped(mask);
                    for (com.ootruffle.clientmetics.cosmetic.CosmeticOptions.Flag flag
                            : com.ootruffle.clientmetics.cosmetic.CosmeticOptions.flags(entry)) {
                        if (flag.label.contains("helmet")) {
                            flag.toggle(com.ootruffle.clientmetics.cosmetic.CosmeticOptions.equipped(LunarAccount.state(), mask));
                        }
                    }
                    final com.ootruffle.clientmetics.cosmetic.CosmeticOptions.Height height =
                            com.ootruffle.clientmetics.cosmetic.CosmeticOptions.height(entry);
                    if (height != null) {
                        height.nudge(com.ootruffle.clientmetics.cosmetic.CosmeticOptions.equipped(LunarAccount.state(), mask), 1);
                        height.nudge(com.ootruffle.clientmetics.cosmetic.CosmeticOptions.equipped(LunarAccount.state(), mask), 1);
                    }
                    final com.ootruffle.clientmetics.cosmetic.EquippedCosmetic after =
                            com.ootruffle.clientmetics.cosmetic.CosmeticOptions.equipped(LunarAccount.state(), mask);
                    Clientmetics.LOGGER.info("[autotest] mask {}: showOverHelmet={} height={} (range {})", entry.name,
                            after.showOverHelmet, after.hatHeightOffset, height == null ? "none" : height.min + ".." + height.max);
                    // Colours: blue cloak, green mask, pink pet (put back in step 4).
                    final int[] colours = {0x3B6BFF, 0x3BD94A, 0xFF3BC4};
                    int i = 0;
                    for (com.ootruffle.clientmetics.cosmetic.EquippedCosmetic c : state.equipped) {
                        com.ootruffle.clientmetics.cosmetic.Recolours.setOwn("lunar:" + c.id, colours[i++ % colours.length]);
                        Clientmetics.LOGGER.info("[autotest] coloured {} ({}): {}", catalog.entry(c.id).name, catalog.entry(c.id).category,
                                Integer.toHexString(com.ootruffle.clientmetics.cosmetic.Recolours.colour(mc.thePlayer.getUniqueID(), "lunar:" + c.id)));
                    }
                }
                next();
                break;
            }
            case 4:
                if (ticks > 40) {
                    screenshot(mc, "clientmetics-wardrobe-settings.png");
                    for (com.ootruffle.clientmetics.cosmetic.EquippedCosmetic c : LunarAccount.state().equipped) {
                        com.ootruffle.clientmetics.cosmetic.Recolours.setOwn("lunar:" + c.id, null);
                    }
                    // Memory: free everything, then check it all loads and draws again.
                    final int before = com.ootruffle.clientmetics.asset.Textures.uploadedFrames();
                    com.ootruffle.clientmetics.asset.MemoryJanitor.sweepAll();
                    Clientmetics.LOGGER.info("[autotest] freed everything: {} texture frames before, {} after",
                            before, com.ootruffle.clientmetics.asset.Textures.uploadedFrames());
                    ((com.ootruffle.clientmetics.render.WardrobeDev) mc.currentScreen).devSelectEquipped(-1);
                    next();
                }
                break;
            case 5:
                if (ticks > 200) {
                    Clientmetics.LOGGER.info("[autotest] after reload: {} texture frames",
                            com.ootruffle.clientmetics.asset.Textures.uploadedFrames());
                    screenshot(mc, "clientmetics-wardrobe-reloaded.png");
                    next();
                }
                break;
            case 6:
                Clientmetics.LOGGER.info("[autotest] done, quitting");
                mc.shutdown();
                next();
                break;
            default:
                break;
        }
    }

    private static Runnable restoreTheme;

    private static void dawnTick() {
        final Minecraft mc = Minecraft.getMinecraft();
        final com.ootruffle.clientmetics.asset.DawnCatalog catalog = com.ootruffle.clientmetics.asset.DawnCatalog.get();
        ticks++;
        Config.get().termsNoticeSeen = true; // In memory only: the notice would cover the screenshots.
        mc.gameSettings.pauseOnLostFocus = false; // Or the pause menu covers them when the window isn't focused.
        if (step == 0) {
            if (catalog != null && mc.currentScreen instanceof GuiMainMenu && mc.theWorld == null) {
                mc.launchIntegratedServer("clientmetics-dawn", "clientmetics-dawn",
                        new WorldSettings(1234L, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT));
                com.ootruffle.clientmetics.asset.Assets.EXECUTOR.execute(DevAutotest::dawnLoadAll);
                next();
            }
        } else if (step <= DAWN_OUTFITS.length) {
            if (mc.thePlayer == null || ticks < (step == 1 ? 100 : 20)) {
                return;
            }
            if (ticks == (step == 1 ? 100 : 20)) {
                mc.gameSettings.hideGUI = true;
                mc.gameSettings.thirdPersonView = step == DAWN_OUTFITS.length ? 1 : 2;
                final java.util.Map<String, String> outfit = new java.util.LinkedHashMap<>();
                for (String type : DAWN_OUTFITS[step - 1]) {
                    final String slug = dawnSample(catalog, type, step - 1);
                    if (slug != null) {
                        outfit.put(type, slug);
                    }
                }
                Clientmetics.LOGGER.info("[autotest] Dawn outfit {}: {}", step, outfit);
                com.ootruffle.clientmetics.cosmetic.DawnCosmeticManager.showOutfit(
                        com.ootruffle.clientmetics.cosmetic.DawnCosmeticSnapshot.of(mc.thePlayer.getUniqueID(), outfit));
            } else if (ticks > 160) {
                screenshot(mc, "clientmetics-dawn-" + step + ".png");
                next();
            }
        } else if (step == DAWN_OUTFITS.length + 1) {
            // The Dawn wardrobe, with demo data: a few cosmetics of each type, three of them equipped.
            mc.gameSettings.hideGUI = false;
            mc.gameSettings.thirdPersonView = 0;
            final List<String> owned = new ArrayList<>();
            for (String type : new String[]{"hat", "face", "back", "wing", "pet", "boot", "cape", "arm", "aura"}) {
                for (int i = 0; i < 3; i++) {
                    final String slug = dawnSample(catalog, type, i);
                    if (slug != null && !owned.contains(slug)) {
                        owned.add(slug);
                    }
                }
            }
            for (com.ootruffle.clientmetics.asset.DawnCatalog.Entry e : catalog.entries()) {
                if ("cape".equals(e.type) && e.texture != null && !e.hasReadableTexture()) {
                    owned.add(e.slug); // One AVIF-only cloak: shows "No preview".
                    break;
                }
            }
            com.ootruffle.clientmetics.cosmetic.DawnAccount.loadDemo(owned);
            for (String slug : new String[]{dawnSample(catalog, "hat", 0), dawnSample(catalog, "wing", 0), dawnSample(catalog, "cape", 0)}) {
                if (slug != null) {
                    com.ootruffle.clientmetics.cosmetic.DawnAccount.toggle(slug);
                }
            }
            Clientmetics.LOGGER.info("[autotest] Dawn wardrobe: {} owned, equipped {}", owned.size(),
                    com.ootruffle.clientmetics.cosmetic.DawnAccount.state().slots);
            com.ootruffle.clientmetics.render.WardrobeSource.select(com.ootruffle.clientmetics.render.WardrobeSource.DAWN);
            // CLIENTMETICS_AUTOTEST_THEME / _ACCENT: try the Compose wardrobe with another theme or accent.
            final String theme = System.getenv("CLIENTMETICS_AUTOTEST_THEME");
            final String accent = System.getenv("CLIENTMETICS_AUTOTEST_ACCENT");
            if (theme != null || accent != null) {
                try {
                    restoreTheme = (Runnable) Class.forName("com.ootruffle.clientmetics.compat.compose.ComposeWardrobe")
                            .getMethod("devTheme", String.class, String.class).invoke(null, theme, accent);
                    Clientmetics.LOGGER.info("[autotest] wardrobe theme {}, accent {}", theme, accent);
                } catch (ReflectiveOperationException | LinkageError e) {
                    Clientmetics.LOGGER.info("[autotest] can't change the wardrobe theme: {}", e.toString());
                }
            }
            mc.displayGuiScreen(Clientmetics.wardrobeScreen());
            next();
        } else if (step == DAWN_OUTFITS.length + 2) {
            if (ticks > 240) {
                screenshot(mc, "clientmetics-dawn-wardrobe.png");
                // OneConfig's own menu, to compare the Compose wardrobe against (OneConfig runs only).
                try {
                    Class.forName("org.polyfrost.oneconfig.api.ui.v1.OneConfigUI").getMethod("open").invoke(null);
                } catch (ReflectiveOperationException | LinkageError e) {
                    Clientmetics.LOGGER.info("[autotest] no OneConfig menu to compare against");
                }
                next();
            }
        } else if (step == DAWN_OUTFITS.length + 3) {
            if (ticks > 200) {
                if (!(mc.currentScreen == null || mc.currentScreen.getClass().getName().startsWith("com.ootruffle"))) {
                    screenshot(mc, "oneconfig-menu.png");
                }
                next();
            }
        } else if (step == DAWN_OUTFITS.length + 4) {
            if (restoreTheme != null) {
                restoreTheme.run();
                restoreTheme = null;
            }
            Clientmetics.LOGGER.info("[autotest] done, quitting");
            mc.shutdown();
            next();
        }
    }

    /** The {@code n}th (by slug) cosmetic of a type that can be drawn from files already on disk. */
    private static String dawnSample(com.ootruffle.clientmetics.asset.DawnCatalog catalog, String type, int n) {
        final List<String> slugs = new ArrayList<>();
        for (com.ootruffle.clientmetics.asset.DawnCatalog.Entry e : catalog.entries()) {
            if (type.equals(e.type) && e.hasReadableTexture() && ("cape".equals(type) || e.geo != null)
                    && AssetFiles.existsLocally(com.ootruffle.clientmetics.asset.DawnFiles.PREFIX + e.texture)) {
                slugs.add(e.slug);
            }
        }
        java.util.Collections.sort(slugs);
        return slugs.isEmpty() ? null : slugs.get(Math.min(n * 7, slugs.size() - 1));
    }

    /** Parses every Dawn model, animation and meta file on disk, to catch files the loader can't read. */
    private static void dawnLoadAll() {
        int ok = 0;
        int failed = 0;
        for (com.ootruffle.clientmetics.asset.DawnCatalog.Entry e : com.ootruffle.clientmetics.asset.DawnCatalog.get().entries()) {
            if (e.geo == null || !e.hasReadableTexture() || "emote".equals(e.type)) {
                continue;
            }
            try {
                com.ootruffle.clientmetics.model.GeoCosmetic.load(com.ootruffle.clientmetics.model.GeoCosmetic.dawnKey(e.slug));
                ok++;
            } catch (Exception ex) {
                failed++;
                Clientmetics.LOGGER.warn("[autotest] Dawn {} {} failed: {}", e.type, e.slug, ex.toString());
            }
        }
        Clientmetics.LOGGER.info("[autotest] Dawn models parsed: {} ok, {} failed", ok, failed);
    }

    private static void next() {
        step++;
        ticks = 0;
    }

    static void screenshot(Minecraft mc, String name) {
        ScreenShotHelper.saveScreenshot(mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        Clientmetics.LOGGER.info("[autotest] saved screenshots/{}", name);
    }

    /** Up to four cosmetics per category whose files are already on disk, so they render offline. */
    private static List<Integer> sampleIds() {
        final Catalog catalog = Catalog.get();
        final List<Integer> out = new ArrayList<>();
        for (String category : CATEGORIES) {
            int found = 0;
            for (Catalog.Entry e : catalog.entries()) {
                if (found >= 4 || !category.equals(e.category) || !AssetFiles.existsLocally(e.resource)) {
                    continue;
                }
                if (!e.geckolib && e.modelName != null) {
                    final Catalog.ModelSettings s = catalog.model(e.modelName);
                    if (s == null || !AssetFiles.existsLocally(s.objPath())) {
                        continue;
                    }
                }
                out.add(e.id);
                found++;
            }
        }
        return out;
    }

}
