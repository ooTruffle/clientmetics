package com.ootruffle.clientmetics.asset;

import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.net.LunarSocket;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads one file of an Essential cosmetic.
 *
 * <p>Essential's catalogue lists each cosmetic's files individually ({@code geometry.steve.json},
 * {@code variants/black/texture.png}, {@code settings.json}), each with its own URL on
 * {@code cdn.essential.gg}, so there is nothing to unpack: a path here is
 * {@code essential:<cosmetic id>/<file path>} and maps straight onto one download.
 *
 * <p>{@link AssetFiles} routes anything with {@link #PREFIX} here, so models and textures load the
 * same way as Lunar's and Dawn's. Blocking; worker threads only.
 */
public final class EssentialFiles {

    /** Marks a path as an Essential cosmetic file rather than a Lunar or Dawn path. */
    public static final String PREFIX = "essential:";

    private static final Set<String> failed = ConcurrentHashMap.newKeySet();
    /** Texture path to its model's {@code texture_width, texture_height}, for splitting frame strips. */
    private static final java.util.Map<String, float[]> TEXTURE_SIZE = new ConcurrentHashMap<>();
    /**
     * How long each frame of an animated Essential texture shows, in ticks. Essential doesn't ship
     * a frame time with the texture; this is an approximation, not Essential's own timing.
     */
    public static final int FRAME_TICKS = 2;

    private EssentialFiles() {}

    static boolean handles(String path) {
        return path.startsWith(PREFIX);
    }

    /**
     * Records the texture size a model expects. Essential's animated textures are a column of
     * frames down the left edge, each the model's texture size (times a whole-number scale for
     * hi-res ones), with no {@code .mcmeta}; some also carry a particle sheet to the right. Without
     * this the whole image is squeezed onto the model and shows as stripes.
     */
    public static void registerFrameAspect(String texturePath, float textureWidth, float textureHeight) {
        if (texturePath != null && textureWidth > 0 && textureHeight > 0) {
            TEXTURE_SIZE.put(texturePath, new float[]{textureWidth, textureHeight});
        }
    }

    /**
     * The {@code {width, height}} of one frame in an image of this size, or null if the texture
     * isn't a known Essential one or doesn't fit its model's texture size.
     */
    static int[] frameSize(String texturePath, int imageWidth, int imageHeight) {
        final float[] size = texturePath == null ? null : TEXTURE_SIZE.get(texturePath);
        if (size == null) {
            return null;
        }
        final int scale = Math.max(1, (int) Math.floor(imageWidth / size[0] + 1e-4));
        final int w = Math.round(size[0] * scale);
        final int h = Math.round(size[1] * scale);
        return w > 0 && h > 0 && w <= imageWidth && h <= imageHeight ? new int[]{w, h} : null;
    }

    /** {@link #frameSize} for the dev autotest. */
    public static int[] frameSizeForTest(String texturePath, int imageWidth, int imageHeight) {
        return frameSize(texturePath, imageWidth, imageHeight);
    }

    /** How many files failed to download this session, for diagnostics. */
    public static int failedCount() {
        return failed.size();
    }

    static byte[] read(String prefixed) throws IOException {
        final Path file = locate(prefixed);
        if (file == null) {
            throw new FileNotFoundException(prefixed + " isn't in Essential's catalogue");
        }
        return Files.readAllBytes(file);
    }

    static boolean existsLocally(String prefixed) {
        try {
            return local(prefixed) != null;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean exists(String prefixed) {
        return asset(prefixed) != null;
    }

    /**
     * The {@code essential:} path of a cosmetic's texture for a variant, or null if it has none. An
     * empty variant means the player hasn't picked one, so the cosmetic's default colour is used.
     * May read {@code settings.json}, so worker threads only.
     */
    public static String texturePath(String id, String variant) {
        final EssentialCatalog.Entry entry = entry(id);
        if (entry == null) {
            return null;
        }
        final String path = entry.textureFor(variant == null || variant.isEmpty() ? defaultVariant(entry) : variant);
        return path == null ? null : PREFIX + id + "/" + path;
    }

    /**
     * The colour Essential shows when the player hasn't chosen one: the first entry of the
     * {@code VARIANTS} property in the cosmetic's {@code settings.json}. "" if it has none.
     * Blocking (may download settings.json); worker threads only.
     */
    public static String defaultVariant(EssentialCatalog.Entry entry) {
        final java.util.List<Variant> variants = variants(entry);
        return variants.isEmpty() ? "" : variants.get(0).name;
    }

    /** One of a cosmetic's colours, from its {@code VARIANTS} setting. */
    public static final class Variant {
        public final String name;
        /** The colour Essential shows for it (RGB), or -1 if it doesn't say. */
        public final int color;

        Variant(String name, int color) {
            this.name = name;
            this.color = color;
        }
    }

    /**
     * The colours a cosmetic comes in, in the order its {@code settings.json} lists them (the first
     * is the default); empty if it has none. Blocking (may download settings.json); worker threads only.
     */
    public static java.util.List<Variant> variants(EssentialCatalog.Entry entry) {
        final java.util.List<Variant> out = new java.util.ArrayList<>();
        final String settings = entry == null ? null : entry.settings();
        if (settings == null) {
            return out;
        }
        try {
            final JsonElement parsed = new JsonParser().parse(new String(
                    read(PREFIX + entry.id + "/" + settings), StandardCharsets.UTF_8));
            if (!parsed.isJsonArray()) {
                return out;
            }
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonObject property = element.getAsJsonObject();
                final JsonElement type = property.get("type");
                final JsonElement data = property.get("data");
                if (type == null || !type.isJsonPrimitive() || !"VARIANTS".equalsIgnoreCase(type.getAsString())
                        || data == null || !data.isJsonObject()) {
                    continue;
                }
                final JsonElement variants = data.getAsJsonObject().get("variants");
                if (variants == null || !variants.isJsonArray()) {
                    continue;
                }
                for (JsonElement variant : variants.getAsJsonArray()) {
                    if (!variant.isJsonObject()) {
                        continue;
                    }
                    final JsonElement name = variant.getAsJsonObject().get("name");
                    final JsonElement color = variant.getAsJsonObject().get("color");
                    if (name != null && name.isJsonPrimitive() && !name.getAsString().isEmpty()) {
                        out.add(new Variant(name.getAsString(), color != null && color.isJsonPrimitive()
                                && color.getAsJsonPrimitive().isNumber() ? color.getAsInt() & 0xFFFFFF : -1));
                    }
                }
                break;
            }
        } catch (IOException | RuntimeException e) {
            if (Config.get().debug) {
                Clientmetics.LOGGER.info("Couldn't read Essential variants for {}: {}", entry.id, e.toString());
            }
        }
        return out;
    }

    /**
     * For recolouring an Essential texture (see {@link Recolour}): the texture of the cosmetic's
     * brightest, most saturated colour to start from, then every colour's texture (to tell the parts
     * that change colour from the ones that don't). Null if the cosmetic has fewer than two
     * colours with their own texture. Blocking; worker threads only.
     */
    public static String[] recolourSources(String texturePath) {
        if (texturePath == null || !handles(texturePath)) {
            return null;
        }
        final String rest = texturePath.substring(PREFIX.length());
        final int slash = rest.indexOf('/');
        final EssentialCatalog.Entry entry = slash < 0 ? null : entry(rest.substring(0, slash));
        if (entry == null) {
            return null;
        }
        final java.util.List<String> paths = new java.util.ArrayList<>();
        String base = null;
        float bestScore = -1;
        for (Variant variant : variants(entry)) {
            final String wanted = "variants/" + variant.name.toLowerCase(Locale.ROOT) + "/texture.png";
            String file = null;
            for (String path : entry.files.keySet()) {
                if (path.toLowerCase(Locale.ROOT).equals(wanted)) {
                    file = path;
                }
            }
            if (file == null) {
                continue;
            }
            final String prefixed = PREFIX + entry.id + "/" + file;
            paths.add(prefixed);
            if (variant.color >= 0) {
                final float[] hsb = java.awt.Color.RGBtoHSB((variant.color >> 16) & 0xFF, (variant.color >> 8) & 0xFF,
                        variant.color & 0xFF, null);
                final float score = hsb[1] * hsb[2];
                if (score > bestScore) {
                    bestScore = score;
                    base = prefixed;
                }
            }
        }
        if (paths.size() < 2) {
            return null;
        }
        paths.add(0, base != null ? base : paths.get(0));
        return paths.toArray(new String[0]);
    }

    /** Like {@link #texturePath} but never downloads: null unless the file is already on disk. */
    public static String cachedTexturePath(String id, String variant) {
        final String path = texturePath(id, variant);
        return path != null && existsLocally(path) ? path : null;
    }

    /** The {@code essential:} path of a cosmetic's geometry, or null. */
    public static String geometryPath(String id) {
        final EssentialCatalog.Entry entry = entry(id);
        final String path = entry == null ? null : entry.geometry();
        return path == null ? null : PREFIX + id + "/" + path;
    }

    private static EssentialCatalog.Entry entry(String id) {
        final EssentialCatalog catalog = EssentialCatalog.get();
        return catalog == null ? null : catalog.entry(id);
    }

    /** The catalogue's asset for a path, or null if it isn't listed. */
    private static EssentialCatalog.Asset asset(String prefixed) {
        final String[] parts = split(prefixed);
        if (parts == null) {
            return null;
        }
        final EssentialCatalog.Entry entry = entry(parts[0]);
        return entry == null ? null : entry.files.get(parts[1]);
    }

    /** {@code id} and the file path inside it. */
    private static String[] split(String prefixed) {
        if (!prefixed.startsWith(PREFIX)) {
            return null;
        }
        final String clean = prefixed.substring(PREFIX.length()).replace('\\', '/');
        final int slash = clean.indexOf('/');
        if (slash <= 0 || slash == clean.length() - 1 || clean.contains("..")) {
            return null;
        }
        return new String[]{clean.substring(0, slash), clean.substring(slash + 1)};
    }

    /** The cached copy, if it's already downloaded. */
    private static Path local(String prefixed) throws IOException {
        final String[] parts = split(prefixed);
        if (parts == null) {
            return null;
        }
        final Path file = cacheDir().resolve(safe(parts[0])).resolve(parts[1]).normalize();
        return file.startsWith(cacheDir()) && Files.isRegularFile(file) ? file : null;
    }

    /** The cached copy, downloading it first if allowed. */
    private static Path locate(String prefixed) throws IOException {
        final Path cached = local(prefixed);
        if (cached != null) {
            return cached;
        }
        final String[] parts = split(prefixed);
        final EssentialCatalog.Asset asset = asset(prefixed);
        if (parts == null || asset == null) {
            return null;
        }
        return download(parts[0], parts[1], asset);
    }

    private static Path cacheDir() {
        return Config.dir().resolve("cache").resolve("essential");
    }

    private static Path download(String id, String path, EssentialCatalog.Asset asset) throws IOException {
        if (!Config.get().downloadMissingAssets) {
            throw new FileNotFoundException(path + " (not downloaded yet, and downloads are off)");
        }
        if (failed.contains(asset.url)) {
            throw new FileNotFoundException(path + " (download failed before)");
        }
        if (!asset.url.startsWith("https://") && !asset.url.startsWith("http://")) {
            throw new IOException("asset URL isn't absolute: " + asset.url);
        }
        try {
            final HttpURLConnection conn = (HttpURLConnection) new URL(asset.url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("User-Agent", LunarSocket.USER_AGENT);
            if (conn.getResponseCode() != 200) {
                throw new IOException("HTTP " + conn.getResponseCode() + " from " + asset.url);
            }
            final byte[] data;
            try (InputStream in = conn.getInputStream()) {
                data = AssetFiles.readLimited(in);
            }
            verify(data, asset.checksum, asset.url);

            final Path target = cacheDir().resolve(safe(id)).resolve(path).normalize();
            if (!target.startsWith(cacheDir())) {
                throw new IOException("bad asset path " + path);
            }
            Files.createDirectories(target.getParent());
            final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            if (Config.get().debug) {
                Clientmetics.LOGGER.info("Fetched Essential {} ({} bytes)", path, data.length);
            }
            return target;
        } catch (IOException e) {
            failed.add(asset.url);
            throw new IOException("couldn't download " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Compares the download against the catalogue's checksum. Essential names its asset files by
     * SHA-256, so that's tried first, then the other common digests. A mismatch is reported but the
     * file is kept: it came from Essential's own asset URL over TLS, and refusing it would blank the
     * wardrobe over a digest choice rather than a bad file.
     */
    private static void verify(byte[] data, String checksum, String url) {
        if (checksum == null || checksum.trim().isEmpty()) {
            return;
        }
        final String expected = checksum.trim().toLowerCase(Locale.ROOT);
        for (String algorithm : new String[]{"SHA-256", "SHA-1", "MD5"}) {
            try {
                if (hex(data, algorithm).equals(expected)) {
                    return;
                }
            } catch (IOException ignored) {
                // Algorithm unavailable; try the next one.
            }
        }
        Clientmetics.LOGGER.warn("Essential asset checksum ({}) matched no known digest for {}; using it anyway",
                checksum, url);
    }

    private static String hex(byte[] data, String algorithm) throws IOException {
        try {
            final StringBuilder out = new StringBuilder();
            for (byte b : MessageDigest.getInstance(algorithm).digest(data)) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IOException("couldn't hash the download", e);
        }
    }

    /** Keeps ids and file paths from escaping the cache directory. */
    private static String safe(String name) {
        final StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            out.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' ? c : '_');
        }
        return out.length() == 0 ? "_" : out.toString();
    }

}
