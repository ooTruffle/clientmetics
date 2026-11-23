package com.ootruffle.clientmetics.asset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.net.LunarSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gets the cosmetics catalogue without a Lunar install:
 * <ol>
 *     <li>POST {@code api.lunarclientprod.com/launcher/launch}; the reply's {@code textures} block
 *     names the textures index ({@code indexUrl}, {@code indexSha1}) and the CDN ({@code baseUrl}).
 *     The request sends this mod's own installation ID, the real OS and the current public launcher
 *     version (the API rejects anything older as {@code OUTDATED_LAUNCHER}), but no hardware ID.</li>
 *     <li>Download that index and check its SHA-1. It maps every file path to a SHA-1.</li>
 *     <li>Download only the catalogue files ({@link #FILES}) from the CDN, each SHA-1 checked,
 *     into {@code config/clientmetics/remote/assets/lunar}.</li>
 * </ol>
 * Cosmetic models and textures are then fetched one by one as players wear them (see
 * {@link AssetFiles}). If Lunar refuses the request, the last downloaded copy is used.
 */
public final class RemoteCatalog {

    private static final String LAUNCH_URL = "https://api.lunarclientprod.com/launcher/launch";
    private static final String UPDATE_URL = "https://launcherupdates.lunarclientcdn.com/latest.yml";
    private static final String DEFAULT_CDN = "https://textures.lunarclientcdn.com/file/";
    /** Only used when the update check fails and no earlier version was cached. */
    private static final String FALLBACK_LAUNCHER_VERSION = "3.4.9";
    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^\\s*version:\\s*['\"]?([^'\"\\s]+)");
    private static final Pattern VERSION_VALUE = Pattern.compile("[0-9]+(?:\\.[0-9]+)+");
    private static final long MAX_FILE = 64L * 1024 * 1024;

    /** Catalogue files, relative to {@code assets/lunar/}. The first two are required. */
    static final String[] FILES = {
            "cosmetics.json",
            "jit_index",
            "cosmetics/index",
            "cosmetics/indexes/hats.json",
            "cosmetics/indexes/bodywear.json",
            "cosmetics/functions.molang",
    };

    private static volatile String cdnBase = DEFAULT_CDN;

    private RemoteCatalog() {}

    /** Where the downloaded catalogue lives, laid out like the launcher's textures folder. */
    public static Path dir() {
        return Config.dir().resolve("remote").resolve("assets").resolve("lunar");
    }

    /** The CDN base URL, from the last launch reply (or the usual default). */
    public static String cdnBase() {
        return cdnBase;
    }

    public static boolean hasCopy() {
        return Files.isRegularFile(dir().resolve("cosmetics.json")) && Files.isRegularFile(dir().resolve("jit_index"));
    }

    /** Refreshes the downloaded catalogue. Returns whether a usable copy exists afterwards. Blocking. */
    public static boolean refresh() {
        loadSavedCdn();
        try {
            final JsonObject textures = requestTextures();
            final String indexUrl = textures.get("indexUrl").getAsString();
            final String indexSha1 = textures.get("indexSha1").getAsString().toLowerCase(Locale.ROOT);
            if (textures.has("baseUrl") && textures.get("baseUrl").isJsonPrimitive()
                    && textures.get("baseUrl").getAsString().startsWith("https://")) {
                String base = textures.get("baseUrl").getAsString();
                if (!base.endsWith("/")) {
                    base += "/";
                }
                cdnBase = base;
                saveCdn(base);
            }

            final byte[] indexData = get(indexUrl);
            if (!sha1(indexData).equals(indexSha1)) {
                throw new IOException("textures index failed its SHA-1 check");
            }
            final Map<String, String> index = parseIndex(indexData);

            int downloaded = 0;
            for (int i = 0; i < FILES.length; i++) {
                final String path = FILES[i];
                final String sha1 = index.get("assets/lunar/" + path);
                if (sha1 == null) {
                    if (i < 2) {
                        throw new IOException("textures index has no " + path);
                    }
                    continue;
                }
                final Path target = dir().resolve(path);
                if (Files.isRegularFile(target) && sha1(Files.readAllBytes(target)).equals(sha1)) {
                    continue; // Already current.
                }
                final byte[] data = get(cdnBase + sha1);
                if (!sha1(data).equals(sha1)) {
                    throw new IOException(path + " failed its SHA-1 check");
                }
                Files.createDirectories(target.getParent());
                final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.write(tmp, data);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                downloaded++;
            }
            Clientmetics.LOGGER.info("Lunar catalogue is up to date ({} files downloaded)", downloaded);
        } catch (Exception e) {
            Clientmetics.LOGGER.warn("Couldn't download the Lunar catalogue: {}{}", e.toString(),
                    hasCopy() ? "; using the copy from last time" : "");
        }
        return hasCopy();
    }

    private static JsonObject requestTextures() throws IOException {
        final String launcherVersion = launcherVersion();
        final JsonObject body = new JsonObject();
        body.addProperty("installation_id", Config.installationId());
        body.addProperty("os", osName());
        body.addProperty("arch", archName());
        body.addProperty("os_release", System.getProperty("os.version", ""));
        body.addProperty("launcher_version", launcherVersion);
        body.addProperty("version", "1.8.9");
        body.addProperty("branch", "master");
        body.addProperty("launch_type", "OFFLINE");
        body.addProperty("module", "lunar");

        final HttpURLConnection conn = open(LAUNCH_URL);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "Lunar Client Launcher v" + launcherVersion);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        final int status = conn.getResponseCode();
        final InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        final String reply = stream == null ? "" : new String(read(stream), StandardCharsets.UTF_8);
        if (status != 200) {
            throw new IOException("launch API answered HTTP " + status + ": " + snippet(reply));
        }
        final JsonElement json = new JsonParser().parse(reply);
        if (json.isJsonObject()) {
            final JsonObject response = json.getAsJsonObject();
            final JsonObject error = response.has("error") && response.get("error").isJsonObject()
                ? response.getAsJsonObject("error") : null;
            final JsonElement errorCode = error == null ? null : error.get("code");
            if (errorCode != null && errorCode.isJsonPrimitive()
                && "OUTDATED_LAUNCHER".equals(errorCode.getAsString())) {
                final JsonElement errorMessage = error.get("message");
                final String message = errorMessage != null && errorMessage.isJsonPrimitive()
                    ? errorMessage.getAsString() : "Lunar requires an updated launcher";
                throw new IOException("Lunar catalogue download blocked (OUTDATED_LAUNCHER): " + message
                    + ". Lunar has raised its minimum launcher version; update Lunar Client, or set"
                    + " catalogueSource to auto/install to read its local catalogue");
            }
        }
        final JsonObject textures = json.isJsonObject() && json.getAsJsonObject().has("textures")
                && json.getAsJsonObject().get("textures").isJsonObject()
                ? json.getAsJsonObject().getAsJsonObject("textures") : null;
        if (textures == null || !textures.has("indexUrl") || !textures.has("indexSha1")) {
            throw new IOException("launch reply has no textures index: " + snippet(reply));
        }
        return textures;
    }

    /**
     * The textures index: one {@code path sha1} pair per line, or JSON (an object of path to
     * SHA-1). Either way, anything that looks like a 40-hex SHA-1 next to a path is taken.
     */
    static Map<String, String> parseIndex(byte[] data) {
        final String text = new String(data, StandardCharsets.UTF_8).trim();
        final Map<String, String> out = new HashMap<>();
        if (text.startsWith("{")) {
            for (Entry<String, JsonElement> e : new JsonParser().parse(text).getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive() && isSha1(e.getValue().getAsString())) {
                    out.put(e.getKey(), e.getValue().getAsString().toLowerCase(Locale.ROOT));
                }
            }
            return out;
        }
        for (String line : text.split("\r?\n")) {
            final String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2 && isSha1(parts[1])) {
                out.put(parts[0], parts[1].toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static boolean isSha1(String s) {
        return s.length() == 40 && s.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }

    private static byte[] get(String url) throws IOException {
        final HttpURLConnection conn = open(url);
        if (conn.getResponseCode() != 200) {
            throw new IOException("HTTP " + conn.getResponseCode() + " for " + url);
        }
        try (InputStream in = conn.getInputStream()) {
            return read(in);
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", LunarSocket.USER_AGENT);
        return conn;
    }

    private static byte[] read(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_FILE) {
                throw new IOException("response too large");
            }
        }
        return out.toByteArray();
    }

    private static String sha1(byte[] data) {
        try {
            final StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-1").digest(data)) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String snippet(String s) {
        final String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }

    private static String osName() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "win32" : os.contains("mac") ? "darwin" : "linux";
    }

    private static String archName() {
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x64";
    }

    /**
     * The current public launcher version, read from Lunar's update feed. The launch API rejects
     * requests that omit it or send an old one ({@code OUTDATED_LAUNCHER}). If the feed is
     * unreachable the last known version is used, and failing that a recent hard-coded one.
     */
    private static String launcherVersion() {
        try {
            final String yml = new String(get(UPDATE_URL), StandardCharsets.UTF_8);
            final Matcher matcher = VERSION_LINE.matcher(yml);
            if (matcher.find() && VERSION_VALUE.matcher(matcher.group(1)).matches()) {
                final String version = matcher.group(1);
                saveLauncherVersion(version);
                return version;
            }
            throw new IOException("latest.yml has no launcher version");
        } catch (Exception e) {
            final String cached = loadSavedLauncherVersion();
            final String version = cached == null ? FALLBACK_LAUNCHER_VERSION : cached;
            Clientmetics.LOGGER.warn("Couldn't check the latest Lunar launcher version ({}); using {}",
                    e.toString(), version);
            return version;
        }
    }

    private static String loadSavedLauncherVersion() {
        try {
            final Path file = Config.dir().resolve("remote").resolve("launcher-version.txt");
            if (Files.isRegularFile(file)) {
                final String version = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
                if (VERSION_VALUE.matcher(version).matches()) {
                    return version;
                }
            }
        } catch (IOException ignored) {
            // Fall through to the hard-coded version.
        }
        return null;
    }

    private static void saveLauncherVersion(String version) {
        try {
            final Path file = Config.dir().resolve("remote").resolve("launcher-version.txt");
            Files.createDirectories(file.getParent());
            Files.write(file, version.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Only a cache.
        }
    }

    private static void loadSavedCdn() {
        try {
            final Path file = Config.dir().resolve("remote").resolve("cdn.txt");
            if (Files.isRegularFile(file)) {
                final String base = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
                if (base.startsWith("https://")) {
                    cdnBase = base;
                }
            }
        } catch (IOException ignored) {
            // Keep the default.
        }
    }

    private static void saveCdn(String base) {
        try {
            final Path file = Config.dir().resolve("remote").resolve("cdn.txt");
            Files.createDirectories(file.getParent());
            Files.write(file, base.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Only a cache.
        }
    }

}
