package com.ootruffle.clientmetics.asset;

import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.net.LunarSocket;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads a Dawn cosmetic file by its asset index name ({@code game/cosmetics/<folder>/<slug>.<ext>}):
 * <ol>
 *     <li>the user's Dawn install ({@code .dawn/assets})</li>
 *     <li>this mod's cache ({@code config/clientmetics/cache/dawn})</li>
 *     <li>if allowed, Dawn's public asset host, checked against the index's SHA-1</li>
 * </ol>
 * {@link AssetFiles} sends paths starting with {@link #PREFIX} here, so textures and models load
 * the same way as Lunar's. Blocking; only call it from a worker thread.
 */
public final class DawnFiles {

    /** Marks a path as a Dawn asset index name rather than a Lunar path. */
    public static final String PREFIX = "dawn:";
    private static final String ASSET_HOST = "https://assets.dawn.gg/";

    private static final Set<String> failedDownloads = ConcurrentHashMap.newKeySet();

    private DawnFiles() {}

    static boolean handles(String path) {
        return path.startsWith(PREFIX);
    }

    static byte[] read(String prefixed) throws IOException {
        final String name = clean(prefixed);
        if (name.contains("..")) {
            throw new IOException("bad path " + prefixed);
        }
        final Path file = findLocal(name);
        return file != null ? Files.readAllBytes(file) : download(name);
    }

    static boolean existsLocally(String prefixed) {
        return findLocal(clean(prefixed)) != null;
    }

    static boolean exists(String prefixed) {
        final DawnCatalog catalog = DawnCatalog.get();
        return existsLocally(prefixed) || catalog != null && catalog.file(clean(prefixed)) != null;
    }

    private static String clean(String prefixed) {
        return prefixed.substring(PREFIX.length()).replace('\\', '/');
    }

    private static Path findLocal(String name) {
        if (name.contains("..")) {
            return null;
        }
        for (Path base : new Path[]{DawnCatalog.installDir().resolve("assets"), ownCache()}) {
            final Path file = base.resolve(name);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    private static Path ownCache() {
        return Config.dir().resolve("cache").resolve("dawn");
    }

    private static byte[] download(String name) throws IOException {
        final DawnCatalog catalog = DawnCatalog.get();
        final DawnCatalog.FileInfo info = catalog == null ? null : catalog.file(name);
        if (info == null) {
            throw new FileNotFoundException(name + " (not in Dawn's asset index)");
        }
        if (!Config.get().downloadMissingAssets) {
            throw new FileNotFoundException(name + " (not downloaded yet, and downloads are off)");
        }
        if (!info.url.startsWith(ASSET_HOST) || failedDownloads.contains(info.sha1)) {
            throw new FileNotFoundException(name + " (download failed before, or isn't on Dawn's asset host)");
        }
        try {
            final HttpURLConnection conn = (HttpURLConnection) new URL(info.url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("User-Agent", LunarSocket.USER_AGENT);
            if (conn.getResponseCode() != 200) {
                throw new IOException("HTTP " + conn.getResponseCode());
            }
            final byte[] data;
            try (InputStream in = conn.getInputStream()) {
                data = AssetFiles.readLimited(in);
            }
            final String sha1 = AssetFiles.sha1(data);
            if (!sha1.equalsIgnoreCase(info.sha1)) {
                throw new IOException("SHA-1 mismatch (got " + sha1 + ")");
            }
            final Path target = ownCache().resolve(name);
            Files.createDirectories(target.getParent());
            final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            if (Config.get().debug) {
                Clientmetics.LOGGER.info("Downloaded Dawn {} ({} bytes)", name, data.length);
            }
            return data;
        } catch (IOException e) {
            failedDownloads.add(info.sha1);
            throw new IOException("couldn't download " + name + ": " + e.getMessage(), e);
        }
    }

}
