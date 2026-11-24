package com.ootruffle.clientmetics.asset;

import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.net.LunarSocket;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads a cosmetic file (path relative to {@code assets/lunar/}) the way the launcher would find it:
 * <ol>
 *     <li>the launcher's own download cache ({@code ~/.lunarclient/jit/assets/lunar-jit})</li>
 *     <li>files shipped with the textures folder ({@code ~/.lunarclient/textures/assets/lunar})</li>
 *     <li>the downloaded catalogue ({@code config/clientmetics/remote}) and this mod's cache
 *     ({@code config/clientmetics/cache})</li>
 *     <li>if allowed, the CDN ({@code <baseUrl>/<sha1>}), checked against {@code jit_index}</li>
 * </ol>
 * Blocking; only call it from a worker thread.
 */
public final class AssetFiles {

    private static final long MAX_DOWNLOAD = 32L * 1024 * 1024;

    /** Hashes the CDN didn't serve, so they aren't asked for again this session. */
    private static final Set<String> failedDownloads = ConcurrentHashMap.newKeySet();

    private AssetFiles() {}

    /** Marks a file shipped inside this mod ({@code assets/clientmetics/...}), such as the bell collar's textures. */
    public static final String BUNDLED = "bundled:";

    public static byte[] read(String path) throws IOException {
        if (path.startsWith(BUNDLED)) {
            final String name = path.substring(BUNDLED.length());
            if (name.contains("..")) {
                throw new IOException("bad path " + path);
            }
            try (InputStream in = AssetFiles.class.getResourceAsStream("/assets/clientmetics/" + name)) {
                if (in == null) {
                    throw new java.io.FileNotFoundException(path);
                }
                return readLimited(in);
            }
        }
        if (DawnFiles.handles(path)) {
            return DawnFiles.read(path);
        }
        if (EssentialFiles.handles(path)) {
            return EssentialFiles.read(path);
        }
        final String clean = Catalog.stripNamespace(path).replace('\\', '/');
        if (clean.contains("..")) {
            throw new IOException("bad path " + path);
        }
        final Path file = findLocal(clean);
        return file != null ? Files.readAllBytes(file) : download(clean);
    }

    /** Whether the file can be found without the network. */
    public static boolean existsLocally(String path) {
        if (path.startsWith(BUNDLED)) {
            return AssetFiles.class.getResource("/assets/clientmetics/" + path.substring(BUNDLED.length())) != null;
        }
        if (DawnFiles.handles(path)) {
            return DawnFiles.existsLocally(path);
        }
        if (EssentialFiles.handles(path)) {
            return EssentialFiles.existsLocally(path);
        }
        return findLocal(Catalog.stripNamespace(path).replace('\\', '/')) != null;
    }

    private static Path findLocal(String clean) {
        for (Path base : new Path[]{LunarInstall.jitCache(), LunarInstall.textures()}) {
            final Path file = base.resolve(clean);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        for (Path base : new Path[]{RemoteCatalog.dir(), ownCache()}) {
            final Path file = base.resolve(clean);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    /** Whether the file exists anywhere: locally or in the launcher's download list. */
    public static boolean exists(String path) {
        if (path.startsWith(BUNDLED)) {
            return existsLocally(path);
        }
        if (DawnFiles.handles(path)) {
            return DawnFiles.exists(path);
        }
        if (EssentialFiles.handles(path)) {
            return EssentialFiles.exists(path);
        }
        final Catalog catalog = Catalog.get();
        return existsLocally(path) || catalog != null && catalog.file(Catalog.stripNamespace(path)) != null;
    }

    private static Path ownCache() {
        return Config.dir().resolve("cache").resolve("assets").resolve("lunar");
    }

    private static byte[] download(String path) throws IOException {
        final Catalog catalog = Catalog.get();
        final Catalog.FileInfo info = catalog == null ? null : catalog.file(path);
        if (info == null) {
            throw new FileNotFoundException(path + " (not in jit_index)");
        }
        if (!Config.get().downloadMissingAssets) {
            throw new FileNotFoundException(path + " (not downloaded yet, and downloads are off)");
        }
        if (failedDownloads.contains(info.sha1) || info.size > MAX_DOWNLOAD) {
            throw new FileNotFoundException(path + " (download failed before)");
        }
        try {
            final HttpURLConnection conn = (HttpURLConnection) new URL(RemoteCatalog.cdnBase() + info.sha1).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("User-Agent", LunarSocket.USER_AGENT);
            if (conn.getResponseCode() != 200) {
                throw new IOException("HTTP " + conn.getResponseCode());
            }
            final byte[] data;
            try (InputStream in = conn.getInputStream()) {
                data = readLimited(in);
            }
            final String sha1 = sha1(data);
            if (!sha1.equalsIgnoreCase(info.sha1)) {
                throw new IOException("SHA-1 mismatch (got " + sha1 + ")");
            }
            final Path target = ownCache().resolve(path);
            Files.createDirectories(target.getParent());
            final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            if (Config.get().debug) {
                Clientmetics.LOGGER.info("Downloaded {} ({} bytes)", path, data.length);
            }
            return data;
        } catch (IOException e) {
            failedDownloads.add(info.sha1);
            throw new IOException("couldn't download " + path + ": " + e.getMessage(), e);
        }
    }

    static byte[] readLimited(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_DOWNLOAD) {
                throw new IOException("file too large");
            }
        }
        return out.toByteArray();
    }

    static String sha1(byte[] data) {
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

}
