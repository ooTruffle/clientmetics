package com.ootruffle.clientmetics.compat;

import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.asset.Assets;
import com.ootruffle.clientmetics.asset.Catalog;
import org.polyfrost.oneconfig.api.commands.v1.CommandManager;
import org.polyfrost.oneconfig.api.config.v1.annotations.Button;
import org.polyfrost.oneconfig.api.config.v1.annotations.Dropdown;
import org.polyfrost.oneconfig.api.config.v1.annotations.Slider;
import org.polyfrost.oneconfig.api.config.v1.annotations.Switch;
import org.polyfrost.oneconfig.api.config.v1.annotations.Text;
import org.polyfrost.oneconfig.utils.v1.dsl.ScreensKt;

/**
 * The OneConfig settings page. Only loaded through {@link OneConfigCompat} when OneConfig is
 * installed. Each field mirrors one in {@link Config}; {@link #sync()} copies changes across and
 * saves {@code config.json}, so the mod reads its settings from one place either way.
 */
public final class ClientmeticsPage extends org.polyfrost.oneconfig.api.config.v1.Config {

    private static final String[] SOURCES = {"auto", "install", "download"};

    static final ClientmeticsPage INSTANCE = new ClientmeticsPage();

    @Switch(title = "Enabled", description = "Master switch: connect and show cosmetics at all (Lunar, Dawn and Essential).", subcategory = "General")
    public boolean enabled = true;

    @Button(title = "Wardrobe", description = "Equip the Lunar, Dawn and Essential cosmetics your accounts own.", text = "Open", subcategory = "General")
    public void openWardrobe() {
        Clientmetics.openWardrobe(); // Next tick, replacing this screen.
    }

    @Switch(title = "Show My Own Cosmetics", description = "Your own equipped Lunar cosmetics, in third person.", subcategory = "General")
    public boolean showOwn = true;

    @Switch(title = "Lunar Cosmetics", description = "Show the Lunar Client cosmetics other players have equipped. Off by itself in Lunar Client, which shows them already.", subcategory = "General")
    public boolean lunar = true;

    @Switch(title = "Dawn Cosmetics", description = "Also show the Dawn Client cosmetics other players have equipped. Off by itself with Dawn Client, which shows them already.", subcategory = "General")
    public boolean dawn = true;

    @Switch(title = "Essential Cosmetics", description = "Show Essential Mod cosmetics and the wardrobe tab. Unofficial: Essential doesn't document or permit this. Off by itself with Essential installed.",
            subcategory = "General")
    public boolean essential = true;

    @Switch(title = "Cosmetic Colours", description = "Show the colours other Clientmetics users picked for their cosmetics, and share yours (pick them in the wardrobe). Uses the Clientmetics colour server.",
            subcategory = "General")
    public boolean recolours = true;

    @Slider(title = "Max Players", description = "How many of the nearest players get cosmetics.", min = 10, max = 500, step = 10, subcategory = "General")
    public int maxPlayers = 200;

    @Slider(title = "Bell Collar Volume", description = "How loud bell collars jingle for you (yours and other players'). 0 mutes them. Only affects what you hear.",
            min = 0, max = 100, step = 5, subcategory = "General")
    public int bellVolume = 100;

    @Switch(title = "Cloaks", subcategory = "Cosmetic Types")
    public boolean showCloaks = true;

    @Switch(title = "Hats, Masks and Glasses", subcategory = "Cosmetic Types")
    public boolean showHats = true;

    @Switch(title = "Bodywear and Backpacks", subcategory = "Cosmetic Types")
    public boolean showBodywear = true;

    @Switch(title = "Pets, Wings, Suits and Shoes", description = "Lunar's animated (GeckoLib) cosmetics.", subcategory = "Cosmetic Types")
    public boolean showGeckolib = true;

    @Switch(title = "Animations", subcategory = "Cosmetic Types")
    public boolean animate = true;

    @Dropdown(title = "Catalogue Source", description = "Where the list of cosmetics comes from. Auto uses a Lunar install if there is one, otherwise downloads it.",
            options = {"Auto", "Lunar install only", "Download only"}, subcategory = "Assets")
    public int catalogueSource = 0;

    @Text(title = "Lunar Folder", description = "Your Lunar data folder. Leave empty for the default (.lunarclient in your home folder).",
            placeholder = "~/.lunarclient", subcategory = "Assets")
    public String lunarDirectory = "";

    @Switch(title = "Download Missing Cosmetics", description = "Fetch cosmetic models and textures from Lunar's CDN when they aren't cached.", subcategory = "Assets")
    public boolean downloadMissingAssets = true;

    @Switch(title = "Debug Logging", description = "Log every outfit and asset load.", subcategory = "Advanced")
    public boolean debug = false;

    private ClientmeticsPage() {
        super("clientmetics.json", "Clientmetics", Category.VISUALS);
    }

    /** Opens this page (from the wardrobe's sidebar). */
    static void openSettings() {
        ScreensKt.openUI(INSTANCE);
    }

    /** Opens OneConfig's mod list (from the wardrobe's sidebar and back button). */
    static void openMods() {
        org.polyfrost.oneconfig.api.ui.v1.OneConfigUI.INSTANCE.open();
    }

    static void init() {
        // Start from config.json, so settings made before OneConfig was installed carry over.
        INSTANCE.copyFrom(Config.get());
        INSTANCE.preload();
        CommandManager.INSTANCE.register(withWardrobe(ScreensKt.addDefaultCommand(INSTANCE, "clientmetics")));
        sync();
    }

    /** Adds {@code wardrobe} to /clientmetics. Generic because the command source type is OneConfig's. */
    private static <S> com.mojang.brigadier.builder.LiteralArgumentBuilder<S> withWardrobe(
            com.mojang.brigadier.builder.LiteralArgumentBuilder<S> root) {
        return root.then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<S>literal("wardrobe")
                .executes(context -> {
                    Clientmetics.openWardrobe();
                    return 1;
                }));
    }

    /** Copies the page's values into {@link Config}, saving and reloading what changed. */
    static void sync() {
        final Config c = Config.get();
        final String source = SOURCES[Math.max(0, Math.min(SOURCES.length - 1, INSTANCE.catalogueSource))];
        final String dir = INSTANCE.lunarDirectory == null ? "" : INSTANCE.lunarDirectory.trim();
        final boolean catalogueChanged = !source.equals(c.catalogueSource) || !dir.equals(c.lunarDirectory);
        final boolean lunarTurnedOn = INSTANCE.lunar && !c.lunar;
        final boolean dawnTurnedOn = INSTANCE.dawn && !c.dawn;
        final boolean essentialTurnedOn = INSTANCE.essential && !c.essential;
        final boolean changed = catalogueChanged
                || c.enabled != INSTANCE.enabled
                || c.lunar != INSTANCE.lunar
                || c.showOwn != INSTANCE.showOwn
                || c.dawn != INSTANCE.dawn
                || c.essential != INSTANCE.essential
                || c.recolours != INSTANCE.recolours
                || c.maxPlayers != INSTANCE.maxPlayers
                || c.bellVolume != INSTANCE.bellVolume
                || c.showCloaks != INSTANCE.showCloaks
                || c.showHats != INSTANCE.showHats
                || c.showBodywear != INSTANCE.showBodywear
                || c.showGeckolib != INSTANCE.showGeckolib
                || c.animate != INSTANCE.animate
                || c.downloadMissingAssets != INSTANCE.downloadMissingAssets
                || c.debug != INSTANCE.debug;
        if (!changed) {
            return;
        }
        c.enabled = INSTANCE.enabled;
        c.lunar = INSTANCE.lunar;
        c.showOwn = INSTANCE.showOwn;
        c.dawn = INSTANCE.dawn;
        c.essential = INSTANCE.essential;
        c.recolours = INSTANCE.recolours;
        c.maxPlayers = INSTANCE.maxPlayers;
        c.bellVolume = INSTANCE.bellVolume;
        c.showCloaks = INSTANCE.showCloaks;
        c.showHats = INSTANCE.showHats;
        c.showBodywear = INSTANCE.showBodywear;
        c.showGeckolib = INSTANCE.showGeckolib;
        c.animate = INSTANCE.animate;
        c.catalogueSource = source;
        c.lunarDirectory = dir;
        c.downloadMissingAssets = INSTANCE.downloadMissingAssets;
        c.debug = INSTANCE.debug;
        Config.save();
        if (!c.lunarOn()) {
            if (Catalog.get() != null) {
                Assets.EXECUTOR.execute(Catalog::unload);
            }
        } else if (catalogueChanged && Catalog.get() != null) {
            Clientmetics.LOGGER.info("Catalogue setting changed; reloading the Lunar catalogue");
            Assets.EXECUTOR.execute(Catalog::load);
        } else if (lunarTurnedOn && c.termsNoticeSeen && Catalog.get() == null) {
            Assets.EXECUTOR.execute(Catalog::load);
        }
        if (dawnTurnedOn && c.dawnOn() && c.termsNoticeSeen && com.ootruffle.clientmetics.asset.DawnCatalog.get() == null) {
            Assets.EXECUTOR.execute(com.ootruffle.clientmetics.asset.DawnCatalog::load);
        }
        if (essentialTurnedOn && c.essentialOn() && c.termsNoticeSeen && com.ootruffle.clientmetics.asset.EssentialCatalog.get() == null) {
            Assets.EXECUTOR.execute(com.ootruffle.clientmetics.asset.EssentialCatalog::load);
        }
    }

    private void copyFrom(Config c) {
        enabled = c.enabled;
        lunar = c.lunar;
        showOwn = c.showOwn;
        dawn = c.dawn;
        essential = c.essential;
        recolours = c.recolours;
        maxPlayers = c.maxPlayers;
        bellVolume = c.bellVolume;
        showCloaks = c.showCloaks;
        showHats = c.showHats;
        showBodywear = c.showBodywear;
        showGeckolib = c.showGeckolib;
        animate = c.animate;
        catalogueSource = 0;
        for (int i = 0; i < SOURCES.length; i++) {
            if (SOURCES[i].equalsIgnoreCase(c.catalogueSource)) {
                catalogueSource = i;
            }
        }
        lunarDirectory = c.lunarDirectory == null ? "" : c.lunarDirectory;
        downloadMissingAssets = c.downloadMissingAssets;
        debug = c.debug;
    }

}
