package com.ootruffle.clientmetics.asset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.net.LunarSocket;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Dawn Client's cosmetics catalogue: two public JSON files, joined by slug.
 * <ul>
 *     <li>the catalogue ({@code /v2/cosmetics/catalog}): slug, type and name of every cosmetic</li>
 *     <li>the asset index ({@code assets_index.json}): name, SHA-1 and URL of every file</li>
 * </ul>
 * Both are read from the user's Dawn install if there is one, otherwise downloaded (no sign-in
 * needed) and kept in {@code config/clientmetics/dawn}. Loaded once, off the client thread.
 */
public final class DawnCatalog {

    private static final String CATALOG_URL = "https://api.dawn.gg/v2/cosmetics/catalog?page_size=1000";
    private static final String INDEX_URL = "https://assets.dawn.gg/assets/assets_index.json";
    private static final String TYPE_PREFIX = "COSMETIC_TYPE_";
    private static final int MAX_PAGES = 50;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Catalogue type (lower case, without {@code COSMETIC_TYPE_}) to its folder under {@code game/cosmetics}. */
    private static final Map<String, String> FOLDERS = new HashMap<>();

    static {
        FOLDERS.put("cape", "cloaks");
        FOLDERS.put("hat", "hats");
        FOLDERS.put("aura", "auras");
        FOLDERS.put("wing", "wings");
        FOLDERS.put("pet", "pets");
        FOLDERS.put("shield", "shields");
        FOLDERS.put("back", "back");
        FOLDERS.put("arm", "arm");
        FOLDERS.put("boot", "boots");
        FOLDERS.put("face", "face");
        FOLDERS.put("emote", "emotes");
        FOLDERS.put("trim", "trim");
    }

    public static final class Entry {
        public final String slug;
        public final String name;
        /** cape, hat, aura, wing, pet, shield, back, arm, boot, face, emote or trim. */
        public final String type;
        /** Asset index names ({@code game/cosmetics/<folder>/<slug>.<ext>}); null where the file doesn't exist. */
        public final String geo;
        public final String animation;
        public final String meta;
        /** The {@code .webp} texture, or the {@code .avif} one if that's all there is. */
        public final String texture;

        Entry(String slug, String name, String type, String geo, String animation, String meta, String texture) {
            this.slug = slug;
            this.name = name;
            this.type = type;
            this.geo = geo;
            this.animation = animation;
            this.meta = meta;
            this.texture = texture;
        }

        /** Whether the texture is one this mod can decode (WebP; there's no AVIF decoder for Java). */
        public boolean hasReadableTexture() {
            return texture != null && texture.endsWith(".webp");
        }
    }

    /** An asset index line. */
    public static final class FileInfo {
        public final String sha1;
        public final String url;

        FileInfo(String sha1, String url) {
            this.sha1 = sha1;
            this.url = url;
        }
    }

    private static volatile DawnCatalog instance;

    private final Map<String, Entry> entries;
    private final Map<String, FileInfo> files;

    private DawnCatalog(Map<String, Entry> entries, Map<String, FileInfo> files) {
        this.entries = entries;
        this.files = files;
    }

    /** The loaded catalogue, or null until {@link #load()} has run (or if it couldn't be read). */
    public static DawnCatalog get() {
        return instance;
    }

    public Entry entry(String slug) {
        return slug == null ? null : entries.get(slug);
    }

    /** {@code name} as in the asset index, e.g. {@code game/cosmetics/hats/angelHalo.webp}. */
    public FileInfo file(String name) {
        return files.get(name);
    }

    public java.util.Collection<Entry> entries() {
        return entries.values();
    }

    public int size() {
        return entries.size();
    }

    /** The user's Dawn data folder: {@code dawnDirectory}, else {@code %APPDATA%\.dawn} or {@code ~/.dawn}. */
    public static Path installDir() {
        final String configured = Config.get().dawnDirectory;
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim());
        }
        final String appData = System.getenv("APPDATA");
        return appData != null && !appData.isEmpty() ? Paths.get(appData, ".dawn")
                : Paths.get(System.getProperty("user.home", "."), ".dawn");
    }

    static Path ownDir() {
        return Config.dir().resolve("dawn");
    }

    /** Reads (or downloads) the catalogue and asset index. Blocking; may use the network. */
    public static synchronized void load() {
        try {
            final Map<String, FileInfo> files = readIndex();
            final JsonArray items = readItems();
            final Map<String, Entry> entries = new HashMap<>();
            int unreadable = 0;
            for (JsonElement e : items) {
                if (!e.isJsonObject()) {
                    continue;
                }
                final JsonObject o = e.getAsJsonObject();
                final String slug = o.has("id") && o.get("id").isJsonObject() ? Catalog.str(o.getAsJsonObject("id"), "value", null) : null;
                String type = Catalog.str(o, "type", "");
                if (type.startsWith(TYPE_PREFIX)) {
                    type = type.substring(TYPE_PREFIX.length());
                }
                type = type.toLowerCase(Locale.ROOT);
                final String folder = FOLDERS.get(type);
                if (slug == null || slug.isEmpty() || folder == null || slug.contains("/") || slug.contains("..")) {
                    continue;
                }
                final String base = "game/cosmetics/" + folder + "/" + slug;
                final Entry entry = new Entry(slug, Catalog.str(o, "displayName", slug), type,
                        known(files, base + ".geo.json"), known(files, base + ".animation.json"), known(files, base + ".meta.json"),
                        files.containsKey(base + ".webp") ? base + ".webp" : known(files, base + ".avif"));
                if (entry.texture != null && !entry.hasReadableTexture()) {
                    unreadable++;
                }
                entries.put(slug, entry);
            }
            instance = new DawnCatalog(Collections.unmodifiableMap(entries), Collections.unmodifiableMap(files));
            Clientmetics.LOGGER.info("Loaded Dawn catalogue: {} cosmetics, {} files ({} have AVIF-only textures and can't be drawn)",
                    entries.size(), files.size(), unreadable);
        } catch (Exception e) {
            Clientmetics.LOGGER.warn("Couldn't load the Dawn catalogue: {}", e.toString());
        }
    }

    private static String known(Map<String, FileInfo> files, String name) {
        return files.containsKey(name) ? name : null;
    }

    /** The asset index: the Dawn install's copy, else downloaded, else the last downloaded copy. */
    private static Map<String, FileInfo> readIndex() throws Exception {
        final Path installed = installDir().resolve("assets").resolve(".index").resolve("assets_index.json");
        final Path cached = ownDir().resolve("assets_index.json");
        String text;
        if (Files.isRegularFile(installed)) {
            text = new String(Files.readAllBytes(installed), StandardCharsets.UTF_8);
        } else {
            try {
                text = httpGet(INDEX_URL);
                save(cached, text);
            } catch (IOException e) {
                if (!Files.isRegularFile(cached)) {
                    throw e;
                }
                Clientmetics.LOGGER.warn("Couldn't download Dawn's asset index ({}); using the last copy", e.toString());
                text = new String(Files.readAllBytes(cached), StandardCharsets.UTF_8);
            }
        }
        final Map<String, FileInfo> files = new HashMap<>();
        for (JsonElement e : new JsonParser().parse(text).getAsJsonArray()) {
            if (!e.isJsonObject()) {
                continue;
            }
            final JsonObject o = e.getAsJsonObject();
            final String name = Catalog.str(o, "name", null);
            final String hash = Catalog.str(o, "hash", null);
            final String url = Catalog.str(o, "url", null);
            if (name != null && hash != null && url != null) {
                files.put(name, new FileInfo(hash, url));
            }
        }
        return files;
    }

    /** Every catalogue item: the Dawn install's cached pages, else downloaded, else the last downloaded copy. */
    private static JsonArray readItems() throws Exception {
        final Path installed = installDir().resolve("cache").resolve("cosmetics").resolve("catalog.json");
        if (Files.isRegularFile(installed)) {
            try {
                // {"at": <millis>, "pages": ["<one response page, as a JSON string>", ...]}
                final JsonArray items = new JsonArray();
                final JsonObject root = Catalog.readJson(installed).getAsJsonObject();
                for (JsonElement page : root.getAsJsonArray("pages")) {
                    items.addAll(new JsonParser().parse(page.getAsString()).getAsJsonObject().getAsJsonArray("items"));
                }
                if (items.size() > 0) {
                    return items;
                }
            } catch (RuntimeException e) {
                Clientmetics.LOGGER.warn("Couldn't read Dawn's cached catalogue ({}); downloading it instead", e.toString());
            }
        }
        final Path cached = ownDir().resolve("catalog.json");
        try {
            final JsonArray items = new JsonArray();
            String token = "";
            for (int page = 0; page < MAX_PAGES; page++) {
                final String url = token.isEmpty() ? CATALOG_URL : CATALOG_URL + "&page_token=" + URLEncoder.encode(token, "UTF-8");
                final JsonObject response = new JsonParser().parse(httpGet(url)).getAsJsonObject();
                if (response.has("items") && response.get("items").isJsonArray()) {
                    items.addAll(response.getAsJsonArray("items"));
                }
                token = response.has("page") && response.get("page").isJsonObject()
                        ? Catalog.str(response.getAsJsonObject("page"), "nextPageToken", "") : "";
                if (token.isEmpty()) {
                    break;
                }
            }
            save(cached, items.toString());
            return items;
        } catch (IOException | RuntimeException e) {
            if (!Files.isRegularFile(cached)) {
                throw e;
            }
            Clientmetics.LOGGER.warn("Couldn't download Dawn's catalogue ({}); using the last copy", e.toString());
            return Catalog.readJson(cached).getAsJsonArray();
        }
    }

    private static String httpGet(String url) throws IOException {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json").header("User-Agent", LunarSocket.USER_AGENT).GET().build();
        try {
            final HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static void save(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Clientmetics.LOGGER.warn("Couldn't save {}: {}", file, e.toString());
        }
    }

}
