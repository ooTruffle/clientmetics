package com.ootruffle.clientmetics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * {@code config/clientmetics/config.json}. Read at startup; missing keys get their defaults written
 * back. With OneConfig installed, its Clientmetics page edits these same settings (see
 * {@link com.ootruffle.clientmetics.compat.OneConfigCompat}).
 */
public final class Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Master switch: connect anywhere and draw cosmetics at all (Lunar, Dawn and Essential). */
    public boolean enabled = true;
    /** Show Lunar Client cosmetics (connects to Lunar's game websocket with the game's own session). */
    public boolean lunar = true;
    /**
     * Where the cosmetics catalogue comes from: {@code auto} (a local Lunar install if there is
     * one, otherwise downloaded), {@code install} (local install only) or {@code download}
     * (always downloaded through Lunar's launcher API, kept in {@code config/clientmetics/remote}).
     */
    public String catalogueSource = "auto";
    /** Your Lunar data folder; empty means {@code ~/.lunarclient}. */
    public String lunarDirectory = "";
    /** Fetch files missing from the launcher's cache from Lunar's CDN (checked against the launcher's SHA-1 list). */
    public boolean downloadMissingAssets = true;
    /** Also show Dawn Client cosmetics (connects to Dawn's gateway with the game's own session). */
    public boolean dawn = true;
    /** Your Dawn data folder; empty means {@code %APPDATA%\.dawn} (or {@code ~/.dawn}). */
    public String dawnDirectory = "";
    /**
     * Show Essential Mod cosmetics and its wardrobe tab. This talks to Essential's own connection
     * manager, which Essential does not document or permit (the first-launch notice says so).
     */
    public boolean essential = true;
    /** Show your own equipped cosmetics in third person. */
    public boolean showOwn = true;
    public boolean showCloaks = true;
    public boolean showHats = true;
    public boolean showBodywear = true;
    public boolean showGeckolib = true;
    public boolean animate = true;
    /** At most this many players are subscribed at once (nearest first). */
    public int maxPlayers = 200;
    /** Log every push and asset load. */
    public boolean debug = false;
    /** Whether the first-launch warning has been accepted; until then nothing is loaded or connects (see {@link Consent}). */
    public boolean termsNoticeSeen = false;
    /**
     * Which version of the warning was accepted. When {@link Consent#NOTICE_VERSION} is newer,
     * {@link #termsNoticeSeen} is reset on load so the new warning is asked.
     */
    public int termsNoticeVersion = 0;

    private static Config instance = new Config();

    /** Lunar cosmetics are wanted: on in the settings, and not running in Lunar Client (which draws its own). */
    public boolean lunarOn() {
        return lunar && !com.ootruffle.clientmetics.compat.HostClients.lunar();
    }

    /** Dawn cosmetics are wanted: on in the settings, and Dawn Client isn't running (it draws its own). */
    public boolean dawnOn() {
        return dawn && !com.ootruffle.clientmetics.compat.HostClients.dawn();
    }

    /** Essential cosmetics are wanted: on in the settings, and Essential isn't installed (it draws its own). */
    public boolean essentialOn() {
        return essential && !com.ootruffle.clientmetics.compat.HostClients.essential();
    }

    public static Config get() {
        return instance;
    }

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("clientmetics");
    }

    /** A random ID kept per install of this mod (not a hardware ID), sent to Lunar as the installation ID. */
    public static synchronized String installationId() {
        final Path file = dir().resolve("installation-id.txt");
        try {
            if (Files.isRegularFile(file)) {
                final String id = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) {
                    return id;
                }
            }
            final String id = java.util.UUID.randomUUID().toString();
            Files.createDirectories(file.getParent());
            Files.write(file, id.getBytes(StandardCharsets.UTF_8));
            return id;
        } catch (Exception e) {
            return java.util.UUID.randomUUID().toString();
        }
    }

    public static void load() {
        final Path file = dir().resolve("config.json");
        try {
            if (Files.isRegularFile(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    final Config loaded = GSON.fromJson(r, Config.class);
                    if (loaded != null) {
                        instance = loaded;
                    }
                }
            }
        } catch (Exception e) {
            Clientmetics.LOGGER.warn("Couldn't read {}: {}", file, e.toString());
        }
        if (instance.termsNoticeSeen && instance.termsNoticeVersion < Consent.NOTICE_VERSION) {
            instance.termsNoticeSeen = false;
            // Before version 2 Essential was off by default, so a saved "off" is almost always just
            // that old default. Turn it on once; the new warning (asked before anything connects)
            // says it's on and how to turn it off. This only runs on the upgrade, since accepting
            // stores the new version.
            if (instance.termsNoticeVersion < 2 && !instance.essential) {
                instance.essential = true;
                Clientmetics.LOGGER.info("Essential cosmetics are now on by default; turning them on (turn off with the Essential Cosmetics setting)");
            }
            Clientmetics.LOGGER.info("The first-launch warning changed (version {} -> {}); asking again",
                    instance.termsNoticeVersion, Consent.NOTICE_VERSION);
        }
        save(); // Writes any keys added since the file was last saved.
    }

    /** Writes the current settings to {@code config.json}. */
    public static synchronized void save() {
        final Path file = dir().resolve("config.json");
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, w);
            }
        } catch (Exception e) {
            Clientmetics.LOGGER.warn("Couldn't save {}: {}", file, e.toString());
        }
    }

}
