package com.ootruffle.clientmetics.asset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.net.VeloApi;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.Node;

/**
 * Velo Client cape art. Two kinds of path, both sent here by {@link AssetFiles} and {@link Textures}:
 * <ul>
 *     <li>{@code velo:cape/<capeId>}: another player's cape, by the ID Velo's online list gives.
 *     {@code custom:<sha256>} is a cape that player uploaded ({@code GET /v1/cape/<sha>}); anything
 *     else is a Velo Store item, looked up with {@code GET /v1/cosmetics/item?id=} and fetched by the
 *     hash it names ({@code GET /v1/cosmetics/file/<sha>}).</li>
 *     <li>{@code velo:local/<file>@<mtime>}: a {@code .velocape} bundle in your own Velo folder (your
 *     own equipped cape, read from disk the way Velo does).</li>
 * </ul>
 * Every downloaded file is named by its SHA-256 and checked against it, and read first from Velo's
 * own cache if Velo is installed, then from {@code config/clientmetics/cache/velo}. Blocking; only
 * call it from a worker thread.
 */
public final class VeloFiles {

    public static final String PREFIX = "velo:";
    private static final String CAPE = PREFIX + "cape/";
    private static final String LOCAL = PREFIX + "local/";

    private static final long MAX_DOWNLOAD = 12L * 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern STORE_ID = Pattern.compile("[a-z0-9_]{1,64}");
    /** Velo samples capes at most 8x the 64x32 layout. */
    private static final int MAX_SCALE = 8;
    /** Decoded frames of one cape may use at most this much memory (and video memory once uploaded). */
    private static final long MAX_PIXELS = 16L * 1024 * 1024;
    private static final int MAX_FRAMES = 400;

    /** Hashes and Store IDs that couldn't be fetched, so they aren't asked for again this session. */
    private static final Set<String> failed = ConcurrentHashMap.newKeySet();
    /** Store item ID to its art's SHA-256. */
    private static final Map<String, String> storeFiles = new ConcurrentHashMap<>();

    private VeloFiles() {}

    static boolean handles(String path) {
        return path.startsWith(PREFIX);
    }

    /** The texture path for a cape ID from Velo's online list, or null if it can't be one. */
    public static String capePath(String capeId) {
        if (capeId == null) {
            return null;
        }
        final String id = capeId.trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("custom:") ? !SHA256.matcher(id.substring(7)).matches() : !STORE_ID.matcher(id).matches()) {
            return null;
        }
        return CAPE + id;
    }

    // ---- your own Velo folder ----

    /** Velo's data folder: {@code -Dvelo.home}, else {@code %APPDATA%\VeloClient} (Windows) or {@code ~/.velo-client}. */
    public static Path installDir() {
        final String home = System.getProperty("velo.home");
        if (home != null && !home.trim().isEmpty()) {
            return Paths.get(home.trim());
        }
        final String appData = System.getenv("APPDATA");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") && appData != null) {
            return Paths.get(appData, "VeloClient");
        }
        return Paths.get(System.getProperty("user.home"), ".velo-client");
    }

    /**
     * The texture path of the cape this account has equipped in its local Velo folder, or null.
     * Like Velo: {@code config/accounts/<uuid>/cosmetics-cape.json}, else {@code config/cosmetics-cape.json},
     * names a bundle by its {@code meta.json} ID. The file's mtime is part of the path, so editing
     * the bundle loads it again.
     */
    public static String localCapePath(java.util.UUID uuid) {
        final Path root = installDir();
        if (!Files.isDirectory(root)) {
            return null;
        }
        String equipped = null;
        final List<Path> configs = new ArrayList<>();
        if (uuid != null) {
            configs.add(root.resolve("config/accounts/" + uuid.toString().replace("-", "") + "/cosmetics-cape.json"));
            configs.add(root.resolve("config/accounts/" + uuid + "/cosmetics-cape.json"));
        }
        configs.add(root.resolve("config/cosmetics-cape.json"));
        for (Path config : configs) {
            if (Files.isRegularFile(config)) {
                final JsonObject json = readJson(config);
                final JsonElement id = json == null ? null : json.get("equippedCapeId");
                equipped = id != null && id.isJsonPrimitive() ? id.getAsString() : null;
                break;
            }
        }
        if (equipped == null || equipped.isEmpty()) {
            return null;
        }
        final Path capes = root.resolve("capes");
        if (!Files.isDirectory(capes)) {
            return null;
        }
        try (java.util.stream.Stream<Path> files = Files.list(capes)) {
            for (Iterator<Path> it = files.iterator(); it.hasNext(); ) {
                final Path bundle = it.next();
                if (!bundle.getFileName().toString().endsWith(".velocape") || !Files.isRegularFile(bundle)) {
                    continue;
                }
                if (equipped.equals(bundleId(bundle))) {
                    return LOCAL + bundle.getFileName() + "@" + Files.getLastModifiedTime(bundle).toMillis();
                }
            }
        } catch (IOException e) {
            if (Config.get().debug) {
                Clientmetics.LOGGER.info("Couldn't list Velo capes in {}: {}", capes, e.toString());
            }
        }
        return null;
    }

    private static String bundleId(Path bundle) {
        try (ZipFile zip = new ZipFile(bundle.toFile())) {
            final ZipEntry meta = zip.getEntry("meta.json");
            if (meta == null || meta.getSize() > 65536) {
                return null;
            }
            try (InputStream in = zip.getInputStream(meta)) {
                final JsonObject json = new JsonParser().parse(new String(AssetFiles.readLimited(in), StandardCharsets.UTF_8)).getAsJsonObject();
                return json.has("id") ? json.get("id").getAsString() : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readLocalBundle(String path) throws IOException {
        final String rest = path.substring(LOCAL.length());
        final int at = rest.lastIndexOf('@');
        final String name = at < 0 ? rest : rest.substring(0, at);
        if (name.contains("/") || name.contains("\\") || name.contains("..") || !name.endsWith(".velocape")) {
            throw new IOException("bad path " + path);
        }
        final Path bundle = installDir().resolve("capes").resolve(name);
        try (ZipFile zip = new ZipFile(bundle.toFile())) {
            // Velo prefers the animated version.
            for (String entryName : new String[]{"frames.gif", "texture.png"}) {
                final ZipEntry entry = zip.getEntry(entryName);
                if (entry != null) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        return limited(in);
                    }
                }
            }
        }
        throw new FileNotFoundException(path + " (no frames.gif or texture.png in the bundle)");
    }

    // ---- other players' capes ----

    static byte[] read(String path) throws IOException {
        if (path.startsWith(LOCAL)) {
            return readLocalBundle(path);
        }
        if (!path.startsWith(CAPE)) {
            throw new IOException("bad path " + path);
        }
        final String id = path.substring(CAPE.length());
        if (id.startsWith("custom:")) {
            final String sha = id.substring(7);
            if (!SHA256.matcher(sha).matches()) {
                throw new IOException("bad cape ID " + id);
            }
            return file(sha, installDir().resolve("cache/remote-capes"), "/v1/cape/" + sha);
        }
        if (!STORE_ID.matcher(id).matches()) {
            throw new IOException("bad cape ID " + id);
        }
        final String sha = storeFile(id);
        return file(sha, installDir().resolve("cache/cosmetics/files"), "/v1/cosmetics/file/" + sha);
    }

    static boolean existsLocally(String path) {
        if (path.startsWith(LOCAL)) {
            return true;
        }
        final String id = path.startsWith(CAPE) ? path.substring(CAPE.length()) : "";
        final String sha = id.startsWith("custom:") ? id.substring(7) : storeFiles.get(id);
        return sha != null && SHA256.matcher(sha).matches() && findLocal(sha) != null;
    }

    static boolean exists(String path) {
        return path.startsWith(LOCAL) || path.startsWith(CAPE);
    }

    /** A Store item's art hash, from {@code GET /v1/cosmetics/item?id=} ({@code files.gif}, else {@code files.texture}). */
    private static String storeFile(String id) throws IOException {
        final String known = storeFiles.get(id);
        if (known != null) {
            return known;
        }
        if (failed.contains("item:" + id)) {
            throw new FileNotFoundException("Velo item " + id + " (lookup failed before)");
        }
        try {
            final JsonObject item = new JsonParser().parse(new String(VeloApi.get("/v1/cosmetics/item?id=" + id, 1024 * 1024),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            final JsonObject files = item.has("files") && item.get("files").isJsonObject() ? item.getAsJsonObject("files") : null;
            String sha = null;
            for (String key : new String[]{"gif", "texture"}) {
                if (sha == null && files != null && files.has(key) && files.get(key).isJsonPrimitive()) {
                    sha = files.get(key).getAsString().toLowerCase(Locale.ROOT);
                }
            }
            if (sha == null || !SHA256.matcher(sha).matches()) {
                throw new IOException("no cape art in the item");
            }
            storeFiles.put(id, sha);
            return sha;
        } catch (IOException | RuntimeException e) {
            failed.add("item:" + id);
            throw new IOException("couldn't look up Velo item " + id + ": " + e.getMessage(), e);
        }
    }

    private static byte[] file(String sha, Path veloCache, String apiPath) throws IOException {
        final Path local = findLocal(sha);
        if (local != null) {
            final byte[] data = Files.readAllBytes(local);
            if (sha.equals(sha256(data))) {
                return data;
            }
        }
        if (!Config.get().downloadMissingAssets) {
            throw new FileNotFoundException(sha + " (not downloaded yet, and downloads are off)");
        }
        if (failed.contains(sha)) {
            throw new FileNotFoundException(sha + " (download failed before)");
        }
        try {
            final byte[] data = VeloApi.get(apiPath, MAX_DOWNLOAD);
            final String got = sha256(data);
            if (!got.equals(sha)) {
                throw new IOException("SHA-256 mismatch (got " + got + ")");
            }
            final Path target = ownCache().resolve(sha);
            Files.createDirectories(target.getParent());
            final Path tmp = target.resolveSibling(sha + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            if (Config.get().debug) {
                Clientmetics.LOGGER.info("Downloaded Velo cape {} ({} bytes)", sha, data.length);
            }
            return data;
        } catch (IOException e) {
            failed.add(sha);
            throw new IOException("couldn't download Velo cape " + sha + ": " + e.getMessage(), e);
        }
    }

    private static Path findLocal(String sha) {
        final Path velo = installDir();
        for (Path file : new Path[]{ownCache().resolve(sha), velo.resolve("cache/remote-capes").resolve(sha),
                velo.resolve("cache/cosmetics/files").resolve(sha)}) {
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    private static Path ownCache() {
        return Config.dir().resolve("cache").resolve("velo");
    }

    // ---- decoding ----

    /**
     * Decodes a cape the way Velo samples it: every GIF frame composited onto the full canvas (with
     * its own delay), and only the top-left 22x17 cells of the 64x32 cape layout kept, at the source's
     * scale rounded down to a power of two (at most 8x, less for long animations).
     */
    static Textures.Decoded decode(String path) throws IOException {
        final byte[] data = AssetFiles.read(path);
        final boolean gif = data.length >= 3 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F';
        if (!gif) {
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null) {
                throw new IOException("unreadable image " + path);
            }
            return new Textures.Decoded(new BufferedImage[]{toCape(image, scaleFor(image.getWidth(), 1))}, new int[]{100});
        }
        final ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(in, false);
            final int count = Math.min(reader.getNumImages(true), MAX_FRAMES);
            if (count <= 0) {
                throw new IOException("empty GIF " + path);
            }
            int width = 0;
            int height = 0;
            final IIOMetadataNode screen = child(reader.getStreamMetadata() == null ? null
                    : (IIOMetadataNode) reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
            if (screen != null) {
                width = intAttr(screen, "logicalScreenWidth", 0);
                height = intAttr(screen, "logicalScreenHeight", 0);
            }
            BufferedImage first = null;
            if (width <= 0 || height <= 0) {
                first = reader.read(0);
                width = first.getWidth();
                height = first.getHeight();
            }
            if ((long) width * height > 64L * 1024 * 1024) {
                throw new IOException("GIF too large (" + width + "x" + height + ")");
            }
            final int scale = scaleFor(width, count);
            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            final BufferedImage[] frames = new BufferedImage[count];
            final int[] delays = new int[count];
            for (int i = 0; i < count; i++) {
                final BufferedImage image = i == 0 && first != null ? first : reader.read(i);
                final IIOMetadataNode meta = (IIOMetadataNode) reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                final IIOMetadataNode desc = child(meta, "ImageDescriptor");
                final IIOMetadataNode control = child(meta, "GraphicControlExtension");
                final int x = desc == null ? 0 : intAttr(desc, "imageLeftPosition", 0);
                final int y = desc == null ? 0 : intAttr(desc, "imageTopPosition", 0);
                final String disposal = control == null ? "none" : control.getAttribute("disposalMethod");
                final int delay = (control == null ? 0 : intAttr(control, "delayTime", 0)) * 10;
                final BufferedImage previous = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;
                final Graphics2D g = canvas.createGraphics();
                g.drawImage(image, x, y, null);
                g.dispose();
                frames[i] = toCape(canvas, scale);
                // Like browsers (and Velo), very short delays play at 100 ms.
                delays[i] = delay < 20 ? 100 : delay;
                if ("restoreToBackgroundColor".equals(disposal)) {
                    final Graphics2D clear = canvas.createGraphics();
                    clear.setComposite(java.awt.AlphaComposite.Clear);
                    clear.fillRect(x, y, image.getWidth(), image.getHeight());
                    clear.dispose();
                } else if (previous != null) {
                    canvas = previous;
                }
            }
            return new Textures.Decoded(frames, delays);
        } finally {
            reader.dispose();
        }
    }

    /** The scale to sample at: the source's, rounded down to a power of two, at most 8x, halved until the frames fit. */
    private static int scaleFor(int width, int frames) {
        int scale = 1;
        while (scale * 2 <= Math.min(width / 64, MAX_SCALE)) {
            scale *= 2;
        }
        while (scale > 1 && (long) frames * 64 * scale * 32 * scale > MAX_PIXELS) {
            scale /= 2;
        }
        return scale;
    }

    /** The 22x17-cell cape region of a 64x32-layout image, on a {@code 64s x 32s} canvas. */
    private static BufferedImage toCape(BufferedImage source, int scale) {
        final int cell = Math.max(1, source.getWidth() / 64);
        final float exact = source.getWidth() / 64f;
        final int srcW = Math.min(source.getWidth(), Math.round(22 * exact));
        final int srcH = Math.min(source.getHeight(), Math.round(17 * exact));
        final BufferedImage out = new BufferedImage(64 * scale, 32 * scale, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = out.createGraphics();
        // Smooth when shrinking, sharp pixels when enlarging (as Velo does).
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale < cell
                ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(source, 0, 0, 22 * scale, 17 * scale, 0, 0, srcW, srcH, null);
        g.dispose();
        return out;
    }

    private static BufferedImage copy(BufferedImage src) {
        final BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static IIOMetadataNode child(IIOMetadataNode node, String name) {
        if (node == null) {
            return null;
        }
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (name.equals(n.getNodeName())) {
                return (IIOMetadataNode) n;
            }
        }
        return null;
    }

    private static int intAttr(IIOMetadataNode node, String name, int fallback) {
        try {
            return Integer.parseInt(node.getAttribute(name));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- helpers ----

    private static JsonObject readJson(Path file) {
        try {
            if (Files.size(file) > 1024 * 1024) {
                return null;
            }
            final JsonElement json = new JsonParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            return json.isJsonObject() ? json.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] limited(InputStream in) throws IOException {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
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

    static String sha256(byte[] data) {
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
