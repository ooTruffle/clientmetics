package com.ootruffle.clientmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.ootruffle.clientmetics.asset.EssentialCatalog;
import com.ootruffle.clientmetics.asset.EssentialFiles;
import com.ootruffle.clientmetics.cosmetic.EssentialAccount;
import com.ootruffle.clientmetics.cosmetic.EssentialCosmeticManager;
import com.ootruffle.clientmetics.cosmetic.EssentialSnapshot;
import com.ootruffle.clientmetics.render.Cloaks;
import com.ootruffle.clientmetics.render.WardrobeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

/**
 * Dev-only check of the Essential side ({@code CLIENTMETICS_AUTOTEST=essential}, see
 * {@link DevAutotest}). Every check logs {@code [autotest] PASS} or {@code [autotest] FAIL}, with a
 * total at the end; outfits and the wardrobe are screenshotted to {@code run/screenshots/}.
 * Nothing is sent to Essential: outfits are shown locally and the wardrobe uses demo data.
 */
final class EssentialAutotest {

    /** Migrator: one of Mojang's capes, listed by Essential under its texture hash. */
    static final String MOJANG_CAPE = "2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933";
    private static final String DISCORD_CAPE = "ESSENTIAL_DISCORD_CAPE";
    private static final String CAPE_DISABLED = "CAPE_DISABLED";

    /** Outfits to wear, each screenshotted from the front and the back. */
    private static final String[][][] OUTFITS = {
            // Default colours (no VARIANT setting): the skirt and glasses must be white, not black.
            {{"PANTS", "SKIRT"}, {"FACE", "3D_GLASSES"}, {"CAPE", DISCORD_CAPE}},
            // A chosen colour (blue skirt) and "Cape Disabled": no cape at all.
            {{"PANTS", "SKIRT"}, {"HAT", "CAT_EARS"}, {"CAPE", CAPE_DISABLED}},
            // A Minecraft cape: not drawn as a model (the dev account has no real cape, so none shows).
            {{"TOP", "HOODIE"}, {"CAPE", MOJANG_CAPE}},
    };
    private static final String BLUE_SKIRT =
            "{\"SKIRT\":[{\"a\":\"UNUSED\",\"b\":\"VARIANT\",\"c\":true,\"d\":{\"variant\":\"blue\"}}]}";

    private static int step;
    private static int ticks;
    private static int passed;
    private static int failed;

    private EssentialAutotest() {}

    static void tick() {
        final Minecraft mc = Minecraft.getMinecraft();
        final Config config = Config.get();
        ticks++;
        // In memory only.
        config.termsNoticeSeen = true;
        config.essential = true;
        config.debug = true;
        mc.gameSettings.pauseOnLostFocus = false;
        final EssentialCatalog catalog = EssentialCatalog.get();
        if (step == 0) {
            if (ticks == 1) {
                com.ootruffle.clientmetics.asset.Assets.EXECUTOR.execute(EssentialCatalog::load);
            }
            if (catalog == null) {
                if (ticks == 400) {
                    Clientmetics.LOGGER.error("[autotest] FAIL no Essential catalogue in {}; copy catalogue.json from an install",
                            Config.dir().resolve("essential"));
                    mc.shutdown();
                }
                return;
            }
            if (mc.currentScreen instanceof GuiMainMenu && mc.theWorld == null) {
                Clientmetics.LOGGER.info("[autotest] Essential catalogue: {} cosmetics; running checks", catalog.size());
                com.ootruffle.clientmetics.asset.Assets.EXECUTOR.execute(() -> {
                    checkCatalogue(catalog);
                    checkVariants(catalog);
                    checkSettingsParsing();
                });
                final Thread all = new Thread(() -> loadAll(catalog), "Clientmetics-Autotest-LoadAll");
                all.setDaemon(true);
                all.start();
                mc.launchIntegratedServer("clientmetics-essential", "clientmetics-essential",
                        new WorldSettings(1234L, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT));
                next();
            }
            return;
        }
        if (mc.thePlayer == null) {
            return;
        }
        final UUID self = mc.thePlayer.getUniqueID();
        // Keep it midday so the screenshots stay readable.
        if (mc.getIntegratedServer() != null && mc.getIntegratedServer().worldServers != null) {
            for (net.minecraft.world.WorldServer world : mc.getIntegratedServer().worldServers) {
                if (world != null) {
                    world.setWorldTime(6000);
                }
            }
        }
        final int outfit = (step - 1) / 2;
        if (outfit < OUTFITS.length) {
            final boolean back = (step - 1) % 2 == 1;
            final int wait = step == 1 ? 100 : 20;
            if (ticks == wait) {
                mc.gameSettings.hideGUI = true;
                mc.gameSettings.thirdPersonView = back ? 1 : 2;
                if (!back) {
                    final EssentialSnapshot snapshot = snapshot(self, outfit);
                    EssentialCosmeticManager.showOutfit(snapshot);
                    Clientmetics.LOGGER.info("[autotest] Essential outfit {}: {}", outfit + 1, snapshot.equipped);
                    checkCape(self, outfit);
                }
            } else if (ticks > wait + 160) {
                if (back) {
                    checkWorn(mc.thePlayer, outfit);
                }
                DevAutotest.screenshot(mc, "clientmetics-essential-" + (outfit + 1) + (back ? "-back" : "-front") + ".png");
                next();
            }
            return;
        }
        final int after = step - 1 - OUTFITS.length * 2;
        if (after == 0) {
            mc.gameSettings.hideGUI = false;
            mc.gameSettings.thirdPersonView = 0;
            // Wardrobe with demo data: owned includes Minecraft capes and "Cape Disabled", which must
            // not be listed; a Minecraft cape is equipped, which must stay listed so it can come off.
            final List<String> owned = new ArrayList<>(Arrays.asList("SKIRT", "3D_GLASSES", "CAT_EARS", "HOODIE",
                    "BOWLER", "AFRO", "PRIDE_CAPE", DISCORD_CAPE, CAPE_DISABLED, MOJANG_CAPE,
                    "cd9d82ab17fd92022dbd4a86cde4c382a7540e117fae7b9a2853658505a80625"));
            final Map<String, String> slots = new LinkedHashMap<>();
            slots.put("PANTS", "SKIRT");
            slots.put("FACE", "3D_GLASSES");
            slots.put("CAPE", MOJANG_CAPE);
            EssentialCosmeticManager.showOutfit(new EssentialSnapshot(self, slots, Collections.<String, JsonArray>emptyMap()));
            EssentialAccount.loadDemo(owned, slots);
            checkWardrobe(catalog, true);
            // Swap the Minecraft cape for the Discord cape through the wardrobe's own toggle.
            EssentialAccount.toggle(DISCORD_CAPE);
            checkWardrobe(catalog, false);
            WardrobeSource.select(WardrobeSource.ESSENTIAL);
            mc.displayGuiScreen(Clientmetics.wardrobeScreen());
            next();
        } else if (after == 1) {
            if (ticks > 240) {
                DevAutotest.screenshot(mc, "clientmetics-essential-wardrobe.png");
                next();
            }
        } else if (after == 2) {
            // With -Pclientmetics.devCompat=true: the Feminine Gender Mod and 3D Skin Layers.
            if (!compatTick(mc, self)) {
                return;
            }
            next();
        } else if (after == 3) {
            // Showcase: outfits drawn from every slot, spread across the catalogue, front and back.
            final int shot = showcaseShot;
            if (shot >= SHOWCASE_ROUNDS * 2) {
                mc.gameSettings.hideGUI = false;
                mc.gameSettings.thirdPersonView = 0;
                next();
                return;
            }
            if (ticks == 1) {
                if (mc.currentScreen != null) {
                    mc.displayGuiScreen(null);
                }
                mc.gameSettings.hideGUI = true;
                mc.gameSettings.thirdPersonView = shot % 2 == 0 ? 2 : 1;
                if (shot % 2 == 0) {
                    final EssentialSnapshot look = showcase(catalog, self, shot / 2);
                    EssentialCosmeticManager.showOutfit(look);
                    Clientmetics.LOGGER.info("[autotest] showcase {}: {}", shot / 2 + 1, look.equipped);
                }
            } else if (ticks > (shot % 2 == 0 ? 120 : 40)) {
                DevAutotest.screenshot(mc, "clientmetics-essential-showcase-" + (shot / 2 + 1) + (shot % 2 == 0 ? "-front" : "-back") + ".png");
                showcaseShot++;
                ticks = 0;
            }
        } else if (after == 4) {
            if (!loadAllDone && ticks < 20 * 600) {
                if (ticks % 200 == 0) {
                    Clientmetics.LOGGER.info("[autotest] waiting for every cosmetic to load...");
                }
                return;
            }
            check("Every wearable cosmetic loads (finished in time)", loadAllDone);
            Clientmetics.LOGGER.info("[autotest] Essential checks: {} passed, {} failed", passed, failed);
            Clientmetics.LOGGER.info("[autotest] done, quitting");
            mc.shutdown();
            next();
        }
    }

    private static EssentialSnapshot snapshot(UUID self, int outfit) {
        final Map<String, String> slots = new LinkedHashMap<>();
        for (String[] slot : OUTFITS[outfit]) {
            slots.put(slot[0], slot[1]);
        }
        final Map<String, JsonArray> settings = new LinkedHashMap<>();
        if (outfit == 1) {
            settings.put("SKIRT", new JsonParser().parse(BLUE_SKIRT).getAsJsonObject().getAsJsonArray("SKIRT"));
        }
        return new EssentialSnapshot(self, slots, settings);
    }

    private static void checkCatalogue(EssentialCatalog catalog) {
        final EssentialCatalog.Entry mojang = catalog.entry(MOJANG_CAPE);
        final EssentialCatalog.Entry disabled = catalog.entry(CAPE_DISABLED);
        final EssentialCatalog.Entry discord = catalog.entry(DISCORD_CAPE);
        check("Migrator is a Minecraft cape", mojang != null && mojang.isMojangCape());
        check("Migrator isn't drawn as a model", !EssentialCatalog.isWearable(mojang));
        check("Cape Disabled is recognised", disabled != null && disabled.isCapeDisabled() && !disabled.isMojangCape());
        check("Cape Disabled isn't drawn as a model", !EssentialCatalog.isWearable(disabled));
        check("Discord cape is an Essential cape", discord != null && !discord.isMojangCape()
                && !discord.isCapeDisabled() && EssentialCatalog.isWearable(discord));
        int mojangCapes = 0;
        for (EssentialCatalog.Entry e : catalog.entries()) {
            if (e.isMojangCape()) {
                mojangCapes++;
                if (!e.id.matches("[0-9a-f]{64}")) {
                    check("Minecraft cape " + e.id + " has a texture-hash id", false);
                }
            }
        }
        Clientmetics.LOGGER.info("[autotest] {} Minecraft capes in the catalogue", mojangCapes);
    }

    /** Every cosmetic with a VARIANTS list must default to its first colour, not the alphabetical first. */
    private static void checkVariants(EssentialCatalog catalog) {
        check("SKIRT defaults to white", "white".equals(EssentialFiles.defaultVariant(catalog.entry("SKIRT"))));
        check("SKIRT default texture is white",
                ("essential:SKIRT/variants/white/texture.png").equals(EssentialFiles.texturePath("SKIRT", "")));
        check("SKIRT blue texture is blue",
                ("essential:SKIRT/variants/blue/texture.png").equals(EssentialFiles.texturePath("SKIRT", "blue")));
        check("3D_GLASSES defaults to white", "white".equals(EssentialFiles.defaultVariant(catalog.entry("3D_GLASSES"))));
        check("A cosmetic without variants keeps its plain texture",
                ("essential:" + DISCORD_CAPE + "/texture.png").equals(EssentialFiles.texturePath(DISCORD_CAPE, "")));
        // Every cached settings.json: the default's texture must exist in the catalogue.
        int ok = 0;
        int bad = 0;
        for (EssentialCatalog.Entry e : catalog.entries()) {
            if (e.settings() == null || !com.ootruffle.clientmetics.asset.AssetFiles.existsLocally(
                    EssentialFiles.PREFIX + e.id + "/" + e.settings())) {
                continue;
            }
            final String variant = EssentialFiles.defaultVariant(e);
            if (variant.isEmpty()) {
                continue;
            }
            final String path = EssentialFiles.texturePath(e.id, "");
            if (path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(
                    "variants/" + variant.toLowerCase(java.util.Locale.ROOT) + "/texture.png")) {
                ok++;
            } else {
                bad++;
                Clientmetics.LOGGER.warn("[autotest] {} default {} but texture {}", e.id, variant, path);
            }
        }
        check("Default colour texture for " + ok + " cached cosmetics", bad == 0 && ok > 0);
    }

    private static volatile boolean loadAllDone;

    /** Outfits worn next to the Feminine Gender Mod and 3D Skin Layers: none, torso-covering, legs only. */
    private static final String[][] COMPAT_OUTFITS = {{}, {"TOP", "HOODIE"}, {"PANTS", "SKIRT"}};
    private static int compatShot;
    private static int compatCallsBefore;
    private static int compatCoveredBefore;
    private static boolean sideShot;

    /** One tick of the compat phase; true once it's finished (or skipped because the mods aren't loaded). */
    private static boolean compatTick(Minecraft mc, UUID self) {
        final net.fabricmc.loader.api.FabricLoader loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        final boolean gender = loader.isModLoaded("feminine_gender_mod");
        final boolean layers = loader.isModLoaded("legacy-skin-layers");
        if (compatShot == 0 && ticks == 1) {
            Clientmetics.LOGGER.info("[autotest] compat: Feminine Gender Mod {}, 3D Skin Layers {}",
                    gender ? "loaded" : "absent", layers ? "loaded" : "absent");
        }
        if (!gender && !layers) {
            return true;
        }
        final int outfit = compatShot / 2;
        if (outfit >= COMPAT_OUTFITS.length) {
            mc.gameSettings.hideGUI = false;
            mc.gameSettings.thirdPersonView = 0;
            return true;
        }
        final boolean back = compatShot % 2 == 1;
        if (ticks == 1) {
            if (mc.currentScreen != null) {
                mc.displayGuiScreen(null); // The gender mod's first-run screen, or the wardrobe.
            }
            mc.gameSettings.hideGUI = true;
            mc.gameSettings.thirdPersonView = back ? 1 : 2;
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = 15; // Level shots, slightly from above.
            if (!back) {
                if (gender) {
                    makeFemale(self);
                }
                final Map<String, String> slots = new LinkedHashMap<>();
                if (COMPAT_OUTFITS[outfit].length == 2) {
                    slots.put(COMPAT_OUTFITS[outfit][0], COMPAT_OUTFITS[outfit][1]);
                }
                EssentialCosmeticManager.showOutfit(new EssentialSnapshot(self, slots, Collections.<String, JsonArray>emptyMap()));
                Clientmetics.LOGGER.info("[autotest] compat outfit {}: {}", outfit + 1, slots);
                compatCallsBefore = com.ootruffle.clientmetics.compat.FeminineGenderCompat.calls;
                compatCoveredBefore = com.ootruffle.clientmetics.compat.FeminineGenderCompat.covered;
            }
        } else if (back) {
            if (ticks > 40) {
                DevAutotest.screenshot(mc, "clientmetics-essential-compat-" + (outfit + 1) + "-back.png");
                compatShot++;
                ticks = 0;
            }
        } else if (!sideShot) {
            if (ticks > 120) {
                checkCompat(mc.thePlayer, outfit, gender);
                DevAutotest.screenshot(mc, "clientmetics-essential-compat-" + (outfit + 1) + "-front.png");
                sideShot = true;
                ticks = 2;
            }
        } else {
            // Turn the body (not the camera) side-on, so the chest's shape shows.
            mc.thePlayer.renderYawOffset = mc.thePlayer.prevRenderYawOffset = mc.thePlayer.rotationYaw + 70;
            if (ticks > 15) {
                DevAutotest.screenshot(mc, "clientmetics-essential-compat-" + (outfit + 1) + "-side.png");
                sideShot = false;
                compatShot++;
                ticks = 0;
            }
        }
        return false;
    }

    /** Sets the player's gender to female with a large bust, so there's something to test against. */
    private static void makeFemale(UUID self) {
        try {
            final Object data = Class.forName("com.feminine.gender.common.GenderCache").getMethod("getOrCreate", UUID.class)
                    .invoke(null, self);
            final Object settings = data.getClass().getMethod("settings").invoke(data);
            final Class<?> genderClass = Class.forName("com.feminine.gender.common.Gender");
            settings.getClass().getMethod("setGender", genderClass).invoke(settings, genderClass.getField("FEMALE").get(null));
            settings.getClass().getMethod("setBustSize", float.class).invoke(settings, 0.8f);
        } catch (ReflectiveOperationException | LinkageError e) {
            check("Set the Feminine Gender Mod's gender: " + e, false);
        }
    }

    private static void checkCompat(net.minecraft.client.entity.EntityPlayerSP player, int outfit, boolean gender) {
        final boolean jacket = player.isWearing(net.minecraft.entity.player.EnumPlayerModelParts.JACKET);
        final boolean hide = gender && com.ootruffle.clientmetics.compat.FeminineGenderCompat.hideUnderCosmetic(player);
        if (gender) {
            check("compat " + (outfit + 1) + ": the Feminine Gender Mod's layer goes through our hook",
                    com.ootruffle.clientmetics.compat.FeminineGenderCompat.calls > compatCallsBefore + 1);
        }
        switch (outfit) {
            case 0:
                check("compat: no cosmetics, jacket layer shown", jacket);
                if (gender) {
                    check("compat: no cosmetics, breasts shown", !hide);
                }
                break;
            case 1:
                check("compat: hoodie hides the jacket layer (3D Skin Layers' too)", !jacket);
                if (gender) {
                    final com.ootruffle.clientmetics.model.GeoCosmetic cover =
                            com.ootruffle.clientmetics.compat.FeminineGenderCompat.coverFor(player);
                    check("compat: hoodie is fitted over the breasts, not hiding them", !hide && cover != null);
                    check("compat: hoodie's chest layers are its t_shirt (4, 21) then body_outer (4, 4, inflate 0.25)",
                            cover != null && cover.torsoFront.length == 2
                                    && cover.torsoFront[0][0] == 4 && cover.torsoFront[0][1] == 21 && cover.torsoFront[0][2] == 0
                                    && cover.torsoFront[1][0] == 4 && cover.torsoFront[1][1] == 4 && cover.torsoFront[1][2] == 0.25f);
                    check("compat: both breasts drawn with the hoodie", com.ootruffle.clientmetics.compat.FeminineGenderCompat.covered
                            >= compatCoveredBefore + 2);
                }
                break;
            case 2:
                check("compat: skirt keeps the jacket layer", jacket);
                if (gender) {
                    check("compat: skirt keeps the breasts, uncovered", !hide
                            && com.ootruffle.clientmetics.compat.FeminineGenderCompat.coverFor(player) == null);
                }
                break;
            default:
                break;
        }
    }
    private static final int SHOWCASE_ROUNDS = 12;
    /** Alternating so items that overlap (a hat and a head, a full-body suit and a top) don't share an outfit. */
    private static final String[][] SHOWCASE_GROUPS = {
            {"HAT", "FACE", "BACK", "PANTS", "SHOES", "ARMS", "WINGS"},
            {"HEAD", "EARS", "FULL_BODY", "PET", "CAPE", "SHOULDERS", "ACCESSORY"},
            {"TOP", "SKIRT", "WINGS", "EARS", "SHOES"},
    };
    private static int showcaseShot;

    private static EssentialSnapshot showcase(EssentialCatalog catalog, UUID self, int round) {
        final String[] group = SHOWCASE_GROUPS[round % SHOWCASE_GROUPS.length];
        final int pass = round / SHOWCASE_GROUPS.length;
        final int passes = (SHOWCASE_ROUNDS + SHOWCASE_GROUPS.length - 1) / SHOWCASE_GROUPS.length;
        final Map<String, String> slots = new LinkedHashMap<>();
        for (String type : group) {
            final List<String> ids = new ArrayList<>();
            for (EssentialCatalog.Entry e : catalog.entries()) {
                if (type.equals(e.type) && EssentialCatalog.isWearable(e)) {
                    ids.add(e.id);
                }
            }
            if (ids.isEmpty()) {
                continue;
            }
            Collections.sort(ids);
            // Spread the picks across the sorted list, so each pass shows different ones.
            slots.put(type, ids.get((pass * ids.size() / passes + round) % ids.size()));
        }
        return new EssentialSnapshot(self, slots, Collections.<String, JsonArray>emptyMap());
    }

    /**
     * Loads every cosmetic the renderer would draw, the way it would (downloading what isn't
     * cached), and decodes its texture. Each failure is logged with its reason; a summary per slot
     * type follows.
     */
    private static void loadAll(EssentialCatalog catalog) {
        final List<EssentialCatalog.Entry> entries = new ArrayList<>(catalog.entries());
        entries.sort((a, b) -> a.id.compareTo(b.id));
        final Map<String, int[]> perType = new java.util.TreeMap<>();
        final List<String> failures = new ArrayList<>();
        final java.util.Set<String> unattached = new java.util.TreeSet<>();
        int animated = 0;
        int strips = 0;
        int enderFrames = 0;
        int dragonFrames = 0;
        for (EssentialCatalog.Entry e : entries) {
            if (!EssentialCatalog.isWearable(e) || !com.ootruffle.clientmetics.cosmetic.EssentialAccount.EQUIPPABLE.contains(e.type)
                    && !"EFFECT".equals(e.type)) {
                continue;
            }
            final int[] counts = perType.computeIfAbsent(e.type, k -> new int[2]);
            try {
                final com.ootruffle.clientmetics.model.GeoCosmetic geo = com.ootruffle.clientmetics.model.GeoCosmetic.load(
                        com.ootruffle.clientmetics.model.GeoCosmetic.essentialKey(e.id, ""));
                final byte[] png = com.ootruffle.clientmetics.asset.AssetFiles.read(geo.texturePath);
                final java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
                if (image == null) {
                    throw new java.io.IOException("texture " + geo.texturePath + " doesn't decode");
                }
                final int[] frame = EssentialFiles.frameSizeForTest(geo.texturePath, image.getWidth(), image.getHeight());
                if (frame == null) {
                    // Particle-only effects carry a particle sheet rather than a model texture.
                    if (geo.isEmptyModel()) {
                        counts[0]++;
                        continue;
                    }
                    throw new java.io.IOException("texture " + image.getWidth() + "x" + image.getHeight()
                            + " doesn't fit the model's texture size");
                }
                if (image.getHeight() >= 2 * frame[1]) {
                    if (image.getHeight() % frame[1] != 0 && !geo.isEmptyModel()) {
                        throw new java.io.IOException("strip " + image.getWidth() + "x" + image.getHeight()
                                + " doesn't split into " + frame[0] + "x" + frame[1] + " frames");
                    }
                    strips++;
                    if ("ENDER_WINGS".equals(e.id)) {
                        enderFrames = image.getHeight() / frame[1];
                    }
                    if ("DEEP_DARK_DRAGON".equals(e.id)) {
                        dragonFrames = image.getHeight() / frame[1] * 1000 + frame[0];
                    }
                }
                if (e.animation() != null) {
                    animated++;
                }
                if (!attachesToPlayer(e)) {
                    unattached.add(e.type + ":" + e.id);
                }
                counts[0]++;
            } catch (Exception ex) {
                counts[1]++;
                failures.add(e.type + " " + e.id + ": " + ex);
                Clientmetics.LOGGER.warn("[autotest] Essential {} {} failed: {}", e.type, e.id, ex.toString());
            }
        }
        for (Map.Entry<String, int[]> t : perType.entrySet()) {
            Clientmetics.LOGGER.info("[autotest] {}: {} loaded, {} failed", t.getKey(), t.getValue()[0], t.getValue()[1]);
        }
        Clientmetics.LOGGER.info("[autotest] {} of the loaded cosmetics have an animation file", animated);
        Clientmetics.LOGGER.info("[autotest] {} cosmetics have animated (frame strip) textures", strips);
        check("Ender Wings texture splits into 16 frames (got " + enderFrames + ")", enderFrames == 16);
        check("Deep Dark Dragon splits into 32 frames 80 wide (got " + dragonFrames / 1000 + " x " + dragonFrames % 1000 + ")",
                dragonFrames == 32 * 1000 + 80);
        if (!unattached.isEmpty()) {
            Clientmetics.LOGGER.info("[autotest] no bone follows the player for: {}", unattached);
        }
        check("Every wearable Essential cosmetic loads (" + failures.size() + " failed)", failures.isEmpty());
        checkSkinCover(catalog);
        checkBoneHiding();
        loadAllDone = true;
    }

    /** Whether any bone of the cosmetic's geometry is one the renderer attaches to a player part. */
    private static boolean attachesToPlayer(EssentialCatalog.Entry e) throws Exception {
        final com.google.gson.JsonElement json = new JsonParser().parse(new String(com.ootruffle.clientmetics.asset.AssetFiles.read(
                EssentialFiles.PREFIX + e.id + "/" + e.geometry()), java.nio.charset.StandardCharsets.UTF_8));
        final String text = json.toString().toLowerCase(java.util.Locale.ROOT);
        for (String bone : new String[]{"head", "body", "arm_right", "arm_left", "leg_right", "leg_left"}) {
            if (text.contains("\"name\":\"" + bone + "\"")) {
                return true;
            }
        }
        return false;
    }

    private static void checkSettingsParsing() {
        final EssentialSnapshot shortKeys = new EssentialSnapshot(new UUID(0, 1), Collections.singletonMap("PANTS", "SKIRT"),
                Collections.singletonMap("SKIRT", new JsonParser().parse(BLUE_SKIRT).getAsJsonObject().getAsJsonArray("SKIRT")));
        check("VARIANT setting (short keys) is read", "blue".equals(shortKeys.variantOf("SKIRT")));
        final JsonArray longKeys = new JsonParser().parse(
                "[{\"id\":\"UNUSED\",\"type\":\"VARIANT\",\"enabled\":true,\"data\":{\"variant\":\"white\"}}]").getAsJsonArray();
        check("VARIANT setting (long keys) is read", "white".equals(new EssentialSnapshot(new UUID(0, 1),
                Collections.singletonMap("PANTS", "SKIRT"), Collections.singletonMap("SKIRT", longKeys)).variantOf("SKIRT")));
        check("No setting means default", "".equals(new EssentialSnapshot(new UUID(0, 1),
                Collections.singletonMap("PANTS", "SKIRT"), Collections.<String, JsonArray>emptyMap()).variantOf("SKIRT")));
    }

    /** settings.json bone rules between two worn cosmetics. */
    private static void checkBoneHiding() {
        try {
            final Map<String, String> slots = new LinkedHashMap<>();
            slots.put("TOP", "HOODIE");
            slots.put("FULL_BODY", "MAID_OUTFIT");
            slots.put("PANTS", "SKIRT");
            final EssentialSnapshot outfit = new EssentialSnapshot(new UUID(0, 2), slots, Collections.<String, JsonArray>emptyMap());
            // The renderer looks the other cosmetics up through the asset cache: wait for them.
            for (String id : slots.values()) {
                for (int i = 0; i < 100 && com.ootruffle.clientmetics.asset.Assets.get("geo",
                        com.ootruffle.clientmetics.model.GeoCosmetic.essentialKey(id, ""),
                        com.ootruffle.clientmetics.model.GeoCosmetic::load) == null; i++) {
                    Thread.sleep(50);
                }
            }
            final com.ootruffle.clientmetics.model.GeoCosmetic maid = com.ootruffle.clientmetics.model.GeoCosmetic.load(
                    com.ootruffle.clientmetics.model.GeoCosmetic.essentialKey("MAID_OUTFIT", ""));
            final com.ootruffle.clientmetics.model.GeoCosmetic skirt = com.ootruffle.clientmetics.model.GeoCosmetic.load(
                    com.ootruffle.clientmetics.model.GeoCosmetic.essentialKey("SKIRT", ""));
            // Hoodie and maid outfit only (the maid outfit also hides its legs under a skirt).
            final Map<String, String> pair = new LinkedHashMap<>();
            pair.put("TOP", "HOODIE");
            pair.put("FULL_BODY", "MAID_OUTFIT");
            final java.util.Set<String> maidHidden = com.ootruffle.clientmetics.render.CosmeticRenderer.hiddenBones(
                    new EssentialSnapshot(new UUID(0, 2), pair, Collections.<String, JsonArray>emptyMap()), "MAID_OUTFIT", maid);
            check("Maid outfit hides everything under a skirt", com.ootruffle.clientmetics.render.CosmeticRenderer.hiddenBones(
                    outfit, "MAID_OUTFIT", maid).contains("leg_right"));
            final java.util.Set<String> skirtHidden = com.ootruffle.clientmetics.render.CosmeticRenderer.hiddenBones(outfit, "SKIRT", skirt);
            Clientmetics.LOGGER.info("[autotest] under a hoodie: maid outfit hides {}, skirt hides {}", maidHidden, skirtHidden);
            check("Maid outfit hides its body and arms under a hoodie, keeps its legs", maidHidden != null
                    && maidHidden.containsAll(Arrays.asList("body", "arm_right", "arm_left"))
                    && !maidHidden.contains("leg_right"));
            check("Hoodie hides the skirt's body_outer", skirtHidden != null && skirtHidden.contains("body_outer"));
            final EssentialSnapshot alone = new EssentialSnapshot(new UUID(0, 2), Collections.singletonMap("TOP", "HOODIE"),
                    Collections.<String, JsonArray>emptyMap());
            check("A hoodie on its own hides nothing of itself", com.ootruffle.clientmetics.render.CosmeticRenderer.hiddenBones(
                    alone, "HOODIE", com.ootruffle.clientmetics.model.GeoCosmetic.load(
                            com.ootruffle.clientmetics.model.GeoCosmetic.essentialKey("HOODIE", ""))) == null);
        } catch (Exception e) {
            check("Bone hiding rules: " + e, false);
        }
    }

    /** Masks: which outer skin layers each cosmetic hides (see SkinCover). */
    private static void checkSkinCover(EssentialCatalog catalog) {
        final int sleeves = com.ootruffle.clientmetics.model.SkinCover.LEFT_SLEEVE | com.ootruffle.clientmetics.model.SkinCover.RIGHT_SLEEVE;
        final int pants = com.ootruffle.clientmetics.model.SkinCover.LEFT_PANTS | com.ootruffle.clientmetics.model.SkinCover.RIGHT_PANTS;
        final int jacket = com.ootruffle.clientmetics.model.SkinCover.JACKET;
        final int hat = com.ootruffle.clientmetics.model.SkinCover.HAT;
        expectCover("HOODIE", jacket | sleeves);
        expectCover("T_SHIRT", jacket | sleeves);
        expectCover("SKIRT", pants);
        expectCover("LONG_HAIR", hat);
        expectCover("3D_GLASSES", 0);
        int covering = 0;
        for (EssentialCatalog.Entry e : catalog.entries()) {
            if (e.files.containsKey("skin_mask.steve.png") && EssentialCatalog.isWearable(e)) {
                covering++;
            }
        }
        Clientmetics.LOGGER.info("[autotest] {} wearable cosmetics ship a skin mask", covering);
    }

    private static void expectCover(String id, int expected) {
        try {
            final com.ootruffle.clientmetics.model.GeoCosmetic geo = com.ootruffle.clientmetics.model.GeoCosmetic.load(
                    com.ootruffle.clientmetics.model.GeoCosmetic.essentialKey(id, ""));
            check(id + " covers skin layers " + Integer.toBinaryString(expected) + " (got "
                    + Integer.toBinaryString(geo.skinCoverSteve) + ")", geo.skinCoverSteve == expected);
        } catch (Exception e) {
            check(id + " skin mask: " + e, false);
        }
    }

    /** In the world: the cape goes through the vanilla layer, and covered skin layers read as not worn. */
    private static void checkWorn(net.minecraft.client.entity.EntityPlayerSP player, int outfit) {
        final UUID self = player.getUniqueID();
        switch (outfit) {
            case 0: // Skirt, glasses, Discord cape.
                check("Discord cape is the vanilla cape texture", Cloaks.capeFor(self) != null
                        && Cloaks.capeFor(self).equals(player.getLocationCape()));
                check("Skirt hides the trouser layers", !player.isWearing(net.minecraft.entity.player.EnumPlayerModelParts.LEFT_PANTS_LEG)
                        && !player.isWearing(net.minecraft.entity.player.EnumPlayerModelParts.RIGHT_PANTS_LEG));
                check("Skirt leaves the jacket layer", player.isWearing(net.minecraft.entity.player.EnumPlayerModelParts.JACKET));
                break;
            case 1: // Skirt, cat ears, Cape Disabled.
                check("Cape Disabled: no cape texture", player.getLocationCape() == null);
                break;
            case 2: // Hoodie, Minecraft cape.
                check("Hoodie hides the jacket and sleeve layers",
                        !player.isWearing(net.minecraft.entity.player.EnumPlayerModelParts.JACKET)
                                && !player.isWearing(net.minecraft.entity.player.EnumPlayerModelParts.LEFT_SLEEVE)
                                && !player.isWearing(net.minecraft.entity.player.EnumPlayerModelParts.RIGHT_SLEEVE));
                check("Hoodie leaves the hat and trouser layers",
                        player.isWearing(net.minecraft.entity.player.EnumPlayerModelParts.HAT)
                                && player.isWearing(net.minecraft.entity.player.EnumPlayerModelParts.LEFT_PANTS_LEG));
                break;
            default:
                break;
        }
    }

    /** Whether the vanilla cape is hidden for each outfit. */
    private static void checkCape(UUID self, int outfit) {
        switch (outfit) {
            case 0:
                check("Discord cape keeps the vanilla cape hidden while it loads", Cloaks.hidesVanillaCape(self));
                break;
            case 1:
                check("Cape Disabled hides the vanilla cape", Cloaks.hidesVanillaCape(self)
                        && Cloaks.capeFor(self) == null);
                break;
            case 2:
                check("Minecraft cape leaves the vanilla cape alone", !Cloaks.hidesVanillaCape(self)
                        && Cloaks.capeFor(self) == null);
                break;
            default:
                break;
        }
    }

    private static void checkWardrobe(EssentialCatalog catalog, boolean mojangEquipped) {
        final List<String> owned = new ArrayList<>();
        final List<String> equipped = new ArrayList<>();
        for (WardrobeSource.Item item : WardrobeSource.ESSENTIAL.owned()) {
            owned.add(item.essentialId);
        }
        for (WardrobeSource.Item item : WardrobeSource.ESSENTIAL.equipped()) {
            equipped.add(item.essentialId);
        }
        Clientmetics.LOGGER.info("[autotest] wardrobe owned {} equipped {}", owned, equipped);
        if (mojangEquipped) {
            check("Wardrobe doesn't offer Minecraft capes", !owned.contains(MOJANG_CAPE)
                    && !owned.contains("cd9d82ab17fd92022dbd4a86cde4c382a7540e117fae7b9a2853658505a80625"));
            check("Wardrobe doesn't offer Cape Disabled", !owned.contains(CAPE_DISABLED));
            check("Wardrobe offers Essential capes", owned.contains(DISCORD_CAPE) && owned.contains("PRIDE_CAPE"));
            check("Wardrobe offers owned cosmetics", owned.contains("SKIRT") && owned.contains("BOWLER"));
            check("An equipped Minecraft cape stays listed", equipped.contains(MOJANG_CAPE));
            check("Owned count matches the listed cards", WardrobeSource.ESSENTIAL.ownedCount() == owned.size());
            for (WardrobeSource.Item item : WardrobeSource.ESSENTIAL.equipped()) {
                if (MOJANG_CAPE.equals(item.essentialId)) {
                    check("An equipped Minecraft cape has no model preview", !item.drawable);
                }
            }
        } else {
            check("Equipping the Discord cape replaces the Minecraft cape",
                    equipped.contains(DISCORD_CAPE) && !equipped.contains(MOJANG_CAPE));
            final EssentialAccount.State state = EssentialAccount.state();
            check("Discord cape is in the CAPE slot", state != null && DISCORD_CAPE.equals(state.slots.get("CAPE")));
        }
    }

    private static synchronized void check(String what, boolean ok) {
        if (ok) {
            passed++;
            Clientmetics.LOGGER.info("[autotest] PASS {}", what);
        } else {
            failed++;
            Clientmetics.LOGGER.error("[autotest] FAIL {}", what);
        }
    }

    private static void next() {
        step++;
        ticks = 0;
    }

}
