package com.ootruffle.clientmetics.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.Consent;
import com.ootruffle.clientmetics.net.LunarSocket;
import com.ootruffle.clientmetics.render.Notice;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;

/**
 * Updates the mod from its GitHub releases.
 * <ol>
 *     <li>Once per launch (after the first-launch notice is accepted, and with
 *     {@code checkForUpdates} on) it asks GitHub for the latest release of
 *     {@code ooTruffle/clientmetics}. Nothing is sent but the request itself.</li>
 *     <li>If that release is newer than this jar, the title screen asks. "Update" downloads the
 *     release's 1.8.9 jar into {@code config/clientmetics/update} (outside {@code mods}, so it's
 *     never loaded from there) and checks it against the SHA-256 GitHub lists for it; a release
 *     with no hash isn't installed.</li>
 *     <li>When the game closes, {@link UpdateApplier} (started from the new jar, as its own
 *     process) waits for it to exit, puts the new jar in {@code mods} and deletes the old one.</li>
 * </ol>
 * A download that couldn't be installed stays pending and is tried again on the next exit. Off in
 * the dev environment, and when the mod isn't a plain jar in a folder (nested in another jar, say).
 */
public final class Updater {

    private static final String REPO = "ooTruffle/clientmetics";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String DOWNLOADS = "https://github.com/" + REPO + "/releases/download/";
    private static final long MAX_JAR = 64L * 1024 * 1024;
    static final String PENDING_FILE = "pending.txt";

    /** A release newer than this jar. */
    private static final class Release {
        final String version;
        final String tag;
        final String notes;
        final String assetName;
        final String url;
        final String sha256;
        final long size;

        Release(String version, String tag, String notes, String assetName, String url, String sha256, long size) {
            this.version = version;
            this.tag = tag;
            this.notes = notes;
            this.assetName = assetName;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    private static volatile Release available;
    private static volatile String status;
    private static boolean checked;
    private static boolean asked;
    private static volatile boolean downloading;
    private static volatile boolean hookAdded;
    /** Work for the client thread: notification buttons are clicked on OneConfig's thread. */
    private static final java.util.Queue<Runnable> TASKS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** The "update available" notification, closed once the download starts. */
    private static volatile Object offer;
    /** Download progress, 0-1. */
    private static volatile float progress;

    /**
     * {@code CLIENTMETICS_AUTOTEST=updater} (dev only): pretend to be version 1.0.0 installed as a
     * fake jar in {@code run/updater-test/mods}, so the real latest release is offered and installed
     * there (see {@code UpdaterAutotest}).
     */
    private static final boolean TEST = FabricLoader.getInstance().isDevelopmentEnvironment()
            && "updater".equals(System.getenv("CLIENTMETICS_AUTOTEST"));

    private Updater() {}

    /** The running jar, if the mod was loaded from a plain jar file in a folder. */
    private static Optional<Path> ownJar() {
        if (TEST) {
            final Path fake = FabricLoader.getInstance().getGameDir().resolve("updater-test/mods/Clientmetics-1.0.0-test.jar");
            try {
                if (!Files.isRegularFile(fake)) {
                    Files.createDirectories(fake.getParent());
                    Files.write(fake, "not a real jar".getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                return Optional.empty();
            }
            return Optional.of(fake.toAbsolutePath());
        }
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return Optional.empty();
        }
        final Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer("clientmetics");
        if (!mod.isPresent()) {
            return Optional.empty();
        }
        try {
            final List<Path> paths = mod.get().getOrigin().getPaths();
            if (paths.size() == 1 && Files.isRegularFile(paths.get(0)) && paths.get(0).toString().endsWith(".jar")) {
                return Optional.of(paths.get(0).toAbsolutePath());
            }
        } catch (RuntimeException e) {
            // Nested or otherwise not a plain file: can't be replaced.
        }
        return Optional.empty();
    }

    private static String currentVersion() {
        if (TEST) {
            return "1.0.0";
        }
        return FabricLoader.getInstance().getModContainer("clientmetics")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("0");
    }

    private static Path dir() {
        return Config.dir().resolve("update");
    }

    /** What the updater last did, for the settings page ("Up to date", "1.2.0 will be installed..."). */
    public static String status() {
        return status;
    }

    /** Called every client tick. */
    public static void onClientTick() {
        if (!Consent.accepted() || !Config.get().checkForUpdates) {
            return;
        }
        if (!checked) {
            checked = true;
            if (!ownJar().isPresent()) {
                return;
            }
            resumePending();
            final Thread thread = new Thread(Updater::check, "Clientmetics-Updater");
            thread.setDaemon(true);
            thread.start();
            return;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        for (Runnable task; (task = TASKS.poll()) != null; ) {
            task.run();
        }
        final Release release = available;
        // A OneConfig notification can go up anywhere; the fallback prompt waits for the title screen.
        if (release != null && !asked && !hookAdded
                && (com.ootruffle.clientmetics.compat.OneConfigCompat.isActive() || mc.currentScreen instanceof GuiMainMenu)) {
            asked = true;
            ask(mc, release);
        }
    }

    private static void ask(Minecraft mc, Release release) {
        final Object notification = com.ootruffle.clientmetics.compat.OneConfigCompat.notifyAsk(
                "Clientmetics " + release.version + " is available",
                "You have " + currentVersion() + ". Update downloads it from GitHub and installs it when you close the game.",
                false, "Update", () -> TASKS.add(() -> startDownload(mc, release)),
                "Not now", () -> Clientmetics.LOGGER.info("Update {} skipped for now; asking again next launch", release.version));
        if (notification != null) {
            offer = notification;
            return;
        }
        final GuiScreen previous = mc.currentScreen;
        final StringBuilder message = new StringBuilder("Clientmetics " + release.version + " is out (you have "
                + currentVersion() + ").\n\n");
        if (!release.notes.isEmpty()) {
            message.append(release.notes).append("\n\n");
        }
        message.append("Update downloads it from GitHub and installs it when you close the game.");
        mc.displayGuiScreen(Notice.create("Clientmetics update", message.toString(), "Update", "Not now", 0, yes -> {
            mc.displayGuiScreen(previous);
            if (yes) {
                startDownload(mc, release);
            } else {
                Clientmetics.LOGGER.info("Update {} skipped for now; asking again next launch", release.version);
            }
        }));
    }

    private static void startDownload(Minecraft mc, Release release) {
        if (downloading) {
            return;
        }
        downloading = true;
        com.ootruffle.clientmetics.compat.OneConfigCompat.dismissNotification(offer);
        offer = null;
        progress = 0;
        status = "Downloading " + release.version + "...";
        final Object bar = com.ootruffle.clientmetics.compat.OneConfigCompat.notifyProgress(
                "Updating Clientmetics", "Downloading " + release.version + "...", () -> progress);
        final Thread thread = new Thread(() -> {
            String error = null;
            try {
                download(release);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                Clientmetics.LOGGER.warn("Couldn't download Clientmetics {}: {}", release.version, e.toString());
                status = "Update failed: " + error;
            } finally {
                downloading = false;
            }
            final String failure = error;
            TASKS.add(() -> {
                com.ootruffle.clientmetics.compat.OneConfigCompat.dismissNotification(bar);
                if (bar != null) {
                    if (failure != null) {
                        com.ootruffle.clientmetics.compat.OneConfigCompat.notifyError("Clientmetics update failed",
                                failure + ". You can get it from github.com/" + REPO + "/releases.");
                        return;
                    }
                    if (com.ootruffle.clientmetics.compat.OneConfigCompat.notifyAsk("Clientmetics " + release.version + " is ready",
                            "It's installed when you close the game, and runs from the next launch.", true,
                            "Quit and install", () -> TASKS.add(mc::shutdown), "Later", () -> { }) != null) {
                        return;
                    }
                }
                final GuiScreen previous = mc.currentScreen;
                if (failure == null) {
                    mc.displayGuiScreen(Notice.create("Clientmetics update",
                            "Clientmetics " + release.version + " is downloaded. It's installed when you close the game, "
                                    + "and runs from the next launch.", "Quit and install", "Later", 0, quit -> {
                                if (quit) {
                                    mc.shutdown();
                                } else {
                                    mc.displayGuiScreen(previous);
                                }
                            }));
                } else {
                    mc.displayGuiScreen(Notice.create("Clientmetics update",
                            "The update couldn't be downloaded: " + failure + "\n\nYou can get it from github.com/" + REPO
                                    + "/releases.", "OK", "Close", 0, ok -> mc.displayGuiScreen(previous)));
                }
            });
        }, "Clientmetics-Updater");
        thread.setDaemon(true);
        thread.start();
    }

    /** The version on offer, or null (dev checks). */
    public static String availableVersion() {
        final Release release = available;
        return release == null ? null : release.version;
    }

    /** Starts the download as if "Update" had been clicked (dev checks). */
    public static void devUpdate() {
        final Release release = available;
        if (TEST && release != null) {
            startDownload(Minecraft.getMinecraft(), release);
        }
    }

    // ---- checking ----

    private static void check() {
        try {
            final Release release = latest();
            if (release == null) {
                status = "Up to date (" + currentVersion() + ")";
                return;
            }
            if (hookAdded) {
                return; // Already downloaded and waiting for the game to close.
            }
            available = release;
            status = release.version + " is available";
            Clientmetics.LOGGER.info("Clientmetics {} is available (running {})", release.version, currentVersion());
        } catch (Exception e) {
            status = "Couldn't check for updates";
            Clientmetics.LOGGER.info("Couldn't check for Clientmetics updates: {}", e.toString());
        }
    }

    /** The latest release if it's newer than this jar and has a usable jar, else null. */
    private static Release latest() throws IOException {
        final JsonObject json = new JsonParser().parse(new String(get(LATEST, 1024 * 1024, "application/vnd.github+json", -1),
                StandardCharsets.UTF_8)).getAsJsonObject();
        if (bool(json, "draft") || bool(json, "prerelease")) {
            return null;
        }
        final String tag = string(json, "tag_name");
        final String version = tag == null ? null : tag.replaceFirst("^[vV]", "");
        if (version == null || compare(version, currentVersion()) <= 0) {
            return null;
        }
        final JsonArray assets = json.has("assets") && json.get("assets").isJsonArray() ? json.getAsJsonArray("assets") : new JsonArray();
        for (JsonElement element : assets) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject asset = element.getAsJsonObject();
            final String name = string(asset, "name");
            final String url = string(asset, "browser_download_url");
            final String digest = string(asset, "digest");
            if (name == null || url == null || !name.endsWith(".jar") || name.contains("-sources") || !name.contains("1.8.9")
                    || !url.startsWith(DOWNLOADS) || name.contains("/") || name.contains("\\") || name.contains("..")) {
                continue;
            }
            final String sha = digest != null && digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(Locale.ROOT) : null;
            if (sha == null || !sha.matches("[0-9a-f]{64}")) {
                Clientmetics.LOGGER.info("Clientmetics {} has no SHA-256 for {}; not offering it", version, name);
                continue;
            }
            final long size = asset.has("size") ? asset.get("size").getAsLong() : -1;
            String notes = string(json, "body");
            notes = notes == null ? "" : notes.replace("\r", "").replaceAll("(?m)^#+\\s*", "").replace("**", "").trim();
            if (notes.length() > 700) {
                notes = notes.substring(0, 700).trim() + "...";
            }
            return new Release(version, tag, notes, name, url, sha, size);
        }
        return null;
    }

    /** Compares dotted versions number by number ("1.10.0" is newer than "1.9.2"; "1.1" equals "1.1.0"). */
    static int compare(String a, String b) {
        final long[] x = numbers(a);
        final long[] y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            final long p = i < x.length ? x[i] : 0;
            final long q = i < y.length ? y[i] : 0;
            if (p != q) {
                return Long.compare(p, q);
            }
        }
        return 0;
    }

    private static long[] numbers(String version) {
        // Only the leading dotted numbers: "1.1.0+1.8.9" is 1.1.0.
        final Matcher m = Pattern.compile("^(\\d+(?:\\.\\d+)*)").matcher(version.trim());
        if (!m.find()) {
            return new long[0];
        }
        final String[] parts = m.group(1).split("\\.");
        final long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Long.parseLong(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = Long.MAX_VALUE;
            }
        }
        return out;
    }

    // ---- downloading and installing ----

    private static void download(Release release) throws IOException {
        final Path jar = ownJar().orElseThrow(() -> new IOException("this copy of the mod can't be updated in place"));
        if (release.size > MAX_JAR) {
            throw new IOException("the release is too large (" + release.size + " bytes)");
        }
        final byte[] data = get(release.url, MAX_JAR, "application/octet-stream", release.size);
        final String sha = sha256(data);
        if (!sha.equals(release.sha256)) {
            throw new IOException("the download doesn't match GitHub's SHA-256 (got " + sha + ")");
        }
        if (data.length < 4 || data[0] != 'P' || data[1] != 'K') {
            throw new IOException("the download isn't a jar");
        }
        Files.createDirectories(dir());
        final Path staged = dir().resolve(release.assetName);
        final Path tmp = dir().resolve(release.assetName + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, staged, StandardCopyOption.REPLACE_EXISTING);
        final Path target = jar.resolveSibling(release.assetName);
        Files.write(dir().resolve(PENDING_FILE), (release.version + "\n" + staged + "\n" + jar + "\n" + target + "\n")
                .getBytes(StandardCharsets.UTF_8));
        installOnExit(release.version, staged, jar, target);
        Clientmetics.LOGGER.info("Downloaded Clientmetics {} ({} bytes, SHA-256 ok); it's installed when the game closes",
                release.version, data.length);
    }

    /** A download from an earlier session that wasn't installed yet: try again when this one closes. */
    private static void resumePending() {
        final Path pending = dir().resolve(PENDING_FILE);
        if (!Files.isRegularFile(pending)) {
            return;
        }
        try {
            final List<String> lines = Files.readAllLines(pending, StandardCharsets.UTF_8);
            final Path jar = ownJar().orElse(null);
            if (lines.size() < 4 || jar == null || compare(lines.get(0), currentVersion()) <= 0) {
                cleanUp(lines.size() >= 2 ? lines.get(1) : null);
                return;
            }
            final Path staged = Path.of(lines.get(1));
            if (!Files.isRegularFile(staged)) {
                cleanUp(null);
                return;
            }
            // Point at the jar that's running now, in case it was moved or renamed since.
            installOnExit(lines.get(0), staged, jar, jar.resolveSibling(staged.getFileName().toString()));
            status = lines.get(0) + " will be installed when the game closes";
            Clientmetics.LOGGER.info("Clientmetics {} was downloaded before but not installed; trying again when the game closes",
                    lines.get(0));
        } catch (Exception e) {
            Clientmetics.LOGGER.warn("Couldn't read the pending update: {}", e.toString());
        }
    }

    private static void cleanUp(String staged) {
        try {
            if (staged != null && Path.of(staged).startsWith(dir())) {
                Files.deleteIfExists(Path.of(staged));
            }
            Files.deleteIfExists(dir().resolve(PENDING_FILE));
        } catch (Exception ignored) {
            // Left for next time.
        }
    }

    private static synchronized void installOnExit(String version, Path staged, Path oldJar, Path target) {
        if (hookAdded) {
            return;
        }
        hookAdded = true;
        available = null;
        status = version + " will be installed when the game closes";
        final String java = ProcessHandle.current().info().command().orElse(
                Path.of(System.getProperty("java.home"), "bin", "java").toString());
        final long pid = ProcessHandle.current().pid();
        // The installer runs from a copy of its class taken from this jar, not from the download:
        // the new jar might not have it (an older release), or have a different one.
        final Path classes = dir().resolve("installer");
        try {
            final String name = UpdateApplier.class.getName().replace('.', '/') + ".class";
            final Path file = classes.resolve(name);
            Files.createDirectories(file.getParent());
            try (InputStream in = Updater.class.getClassLoader().getResourceAsStream(name)) {
                if (in == null) {
                    throw new IOException("the installer class isn't in this jar");
                }
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Clientmetics.LOGGER.warn("Can't install the update when the game closes: {}", e.toString());
            status = "Update downloaded, but it can't be installed automatically: " + e.getMessage();
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                new ProcessBuilder(java, "-cp", classes.toString(), UpdateApplier.class.getName(),
                        Long.toString(pid), staged.toString(), oldJar.toString(), target.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(dir().resolve("updater-process.log").toFile()))
                        .start();
            } catch (Exception e) {
                System.err.println("[Clientmetics] couldn't start the updater: " + e);
            }
        }, "Clientmetics-Update-Installer"));
    }

    // ---- helpers ----

    /** Downloads {@code url}; with {@code expected} bytes known, {@link #progress} follows it. */
    private static byte[] get(String url, long max, String accept, long expected) throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", LunarSocket.USER_AGENT);
        conn.setRequestProperty("Accept", accept);
        try {
            final int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code + " from " + conn.getURL().getHost());
            }
            try (InputStream in = conn.getInputStream()) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (expected > 0) {
                        progress = Math.min(1f, out.size() / (float) expected);
                    }
                    if (out.size() > max) {
                        throw new IOException("response too large");
                    }
                }
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String string(JsonObject object, String key) {
        final JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static boolean bool(JsonObject object, String key) {
        final JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static String sha256(byte[] data) {
        try {
            final StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
