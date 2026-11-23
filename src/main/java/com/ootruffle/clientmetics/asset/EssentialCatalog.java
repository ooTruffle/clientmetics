package com.ootruffle.clientmetics.asset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Essential's cosmetic catalogue, as Essential sends it on sign-in
 * ({@code cosmetic.ServerCosmeticsPopulatePacket}).
 *
 * <p>One entry per cosmetic. Its {@code q} field is a map of <b>file path to asset</b>, not one
 * bundle per variant — a hat looks like:
 * <pre>
 * "q": {
 *   "geometry.steve.json":        {"a": "https://cdn.essential.gg/assets/073a…", "b": "073a…"},
 *   "variants/black/texture.png": {"a": "https://cdn.essential.gg/assets/84e1…", "b": "84e1…"},
 *   "settings.json":              {"a": "https://cdn.essential.gg/assets/7587…", "b": "7587…"},
 *   "skin_mask.steve.png":        {…}
 * }
 * </pre>
 * so files are fetched individually by path. Nothing is fetched until a catalogue arrives, because
 * Essential has no public catalogue document to fall back on.
 *
 * <p>Entries are written to {@code config/clientmetics/essential/catalogue.json} so a restart does
 * not need to wait for a sign-in before showing names and previews.
 */
public final class EssentialCatalog {

    /** Slots Essential knows. Used to label cards and to decide how a cosmetic attaches. */
    public static final List<String> SLOTS = Collections.unmodifiableList(java.util.Arrays.asList(
            "HAT", "HEAD", "FACE", "EARS", "CAPE", "BACK", "WINGS", "TAIL", "PET", "ARMS", "SHOULDERS",
            "SUITS", "TOP", "SKIRT", "PANTS", "SHOES", "FULL_BODY", "ACCESSORY", "EFFECT", "EMOTE", "ICON"));

    /** One file of a cosmetic: where to fetch it and what it must hash to. */
    public static final class Asset {
        public final String url;
        /** Hex SHA-256 of the file (the asset host names files by it). */
        public final String checksum;

        Asset(String url, String checksum) {
            this.url = url;
            this.checksum = checksum;
        }
    }

    public static final class Entry {
        /** Essential's cosmetic id, e.g. {@code HEAD_AFRO}. */
        public final String id;
        /** Slot name, upper case ({@code HAT}, {@code CAPE}, ...). */
        public final String type;
        public final String name;
        /** File path within the cosmetic ({@code geometry.steve.json}) to its asset. */
        public final Map<String, Asset> files;
        /** Essential's tags ({@code h}), e.g. {@code CAPE_UNLOCKABLE}. */
        public final Set<String> tags;

        Entry(String id, String type, String name, Map<String, Asset> files, Set<String> tags) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.files = files;
            this.tags = tags;
        }

        /** Essential's "no cape" entry: the vanilla cape must be hidden and nothing drawn. */
        public boolean isCapeDisabled() {
            return "CAPE_DISABLED".equals(id);
        }

        /**
         * One of Mojang's own capes (Migrator, Vanilla, ...), which Essential lists under the cape
         * texture's hash. Essential doesn't draw these itself: it makes them the account's active
         * Minecraft cape, so the vanilla cape layer shows them, and its wardrobe only offers the ones
         * the Minecraft account has.
         */
        public boolean isMojangCape() {
            return "CAPE".equals(type) && !isCapeDisabled() && tags.contains("CAPE_UNLOCKABLE");
        }

        /** The Bedrock geometry file ({@code geometry.steve.json}), or null. */
        public String geometry() {
            return geometries().isEmpty() ? null : geometries().get(0);
        }

        /**
         * Every geometry file, sorted. Cosmetics with a separate slim-arm model ship
         * {@code geometry.alex.json} alongside {@code geometry.steve.json}; both are needed because
         * the choice depends on the wearer's skin.
         */
        public List<String> geometries() {
            final List<String> out = new ArrayList<>();
            for (String path : files.keySet()) {
                if (path.toLowerCase(Locale.ROOT).startsWith("geometry")
                        || path.toLowerCase(Locale.ROOT).contains("/geometry")) {
                    out.add(path);
                }
            }
            Collections.sort(out);
            return out;
        }

        /** {@code steve} from {@code geometry.steve.json}, or null. */
        public String modelName() {
            final String geometry = geometry();
            if (geometry == null) {
                return null;
            }
            final String base = geometry.substring(geometry.lastIndexOf('/') + 1);
            final String[] parts = base.split("\\.");
            return parts.length >= 3 ? parts[1] : null;
        }

        /**
         * The texture for a variant ({@code variants/black/texture.png}), else any texture. Callers
         * with no variant should pass {@link EssentialFiles#defaultVariant} rather than "", or the
         * alphabetically first colour is used instead of the cosmetic's default.
         */
        public String textureFor(String variant) {
            if (variant != null && !variant.isEmpty()) {
                final String wanted = "variants/" + variant.toLowerCase(Locale.ROOT) + "/texture.png";
                for (String path : files.keySet()) {
                    if (path.toLowerCase(Locale.ROOT).equals(wanted)) {
                        return path;
                    }
                }
            }
            final String named = firstMatching("texture.png");
            if (named != null) {
                return named;
            }
            // Capes and a few other slots ship a plain png instead; the skin mask is never the texture.
            final List<String> paths = new ArrayList<>(files.keySet());
            Collections.sort(paths);
            for (String path : paths) {
                final String lower = path.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".png") && !lower.contains("skin_mask")) {
                    return path;
                }
            }
            return null;
        }

        /** The wardrobe settings file, if the cosmetic has one. */
        public String settings() {
            for (String path : files.keySet()) {
                if (path.equalsIgnoreCase("settings.json")) {
                    return path;
                }
            }
            return null;
        }

        /** The animation file, if the cosmetic has one: Essential ships {@code animations.json}. */
        public String animation() {
            for (String path : files.keySet()) {
                if (path.equalsIgnoreCase("animations.json")) {
                    return path;
                }
            }
            return firstMatching(".animation.json");
        }

        private String firstMatching(String needle) {
            // Sorted so the same cosmetic always picks the same file.
            final List<String> paths = new ArrayList<>(files.keySet());
            Collections.sort(paths);
            for (String path : paths) {
                if (path.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                    return path;
                }
            }
            return null;
        }
    }

    private static volatile EssentialCatalog instance;

    private final Map<String, Entry> entries;

    private EssentialCatalog(Map<String, Entry> entries) {
        this.entries = entries;
    }

    /** The loaded catalogue, or null until one has arrived or been read from disk. */
    public static EssentialCatalog get() {
        return instance;
    }

    /**
     * Whether an entry is a cosmetic a player wears, as opposed to something the wardrobe shouldn't
     * list:
     * <ul>
     *     <li>emotes — animations, not models, and equipped separately from slots;</li>
     *     <li>the per-slot {@code BASE_*} defaults ({@code BASE_ICON}), which are what an empty slot
     *     looks like rather than anything anyone bought.</li>
     * </ul>
     */
    public static boolean isWearable(Entry entry) {
        if (entry == null || entry.id == null) {
            return false;
        }
        if ("EMOTE".equals(entry.type)) {
            return false;
        }
        if (entry.isCapeDisabled() || entry.isMojangCape()) {
            return false; // Not a model: "no cape", or a Minecraft cape the vanilla layer draws.
        }
        return !entry.id.startsWith("BASE_");
    }

    public Entry entry(String id) {
        return id == null ? null : entries.get(id);
    }

    public Collection<Entry> entries() {
        return entries.values();
    }

    public int size() {
        return entries.size();
    }

    static Path ownDir() {
        return Config.dir().resolve("essential");
    }

    private static Path cacheFile() {
        return ownDir().resolve("catalogue.json");
    }

    /** Reads the last catalogue written to disk, if there is one. Blocking; worker threads only. */
    public static synchronized void load() {
        final Path file = cacheFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            final JsonElement json = new JsonParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (json.isJsonArray()) {
                instance = parse(json.getAsJsonArray());
                Clientmetics.LOGGER.info("Loaded Essential catalogue from disk: {} cosmetics", instance.size());
            }
        } catch (Exception e) {
            Clientmetics.LOGGER.warn("Couldn't read Essential's saved catalogue: {}", e.toString());
        }
    }

    /** Replaces the catalogue with one Essential just sent, and saves it for next time. */
    public static synchronized void accept(JsonArray cosmetics) {
        if (cosmetics == null || cosmetics.size() == 0) {
            return;
        }
        instance = parse(cosmetics);
        Clientmetics.LOGGER.info("Essential sent {} cosmetics", instance.size());
        save(cosmetics);
    }

    private static EssentialCatalog parse(JsonArray items) {
        final Map<String, Entry> entries = new HashMap<>();
        for (JsonElement item : items) {
            if (!item.isJsonObject()) {
                continue;
            }
            final JsonObject o = item.getAsJsonObject();
            final String id = Catalog.str(o, "a", null);
            final String type = Catalog.str(o, "b", "");
            if (id == null || id.isEmpty()) {
                continue;
            }
            final Map<String, Asset> files = new HashMap<>();
            if (o.has("q") && o.get("q").isJsonObject()) {
                for (Map.Entry<String, JsonElement> file : o.getAsJsonObject("q").entrySet()) {
                    if (!file.getValue().isJsonObject()) {
                        continue;
                    }
                    final JsonObject asset = file.getValue().getAsJsonObject();
                    // Short keys on the wire, long ones in newer builds: accept either.
                    final String url = Catalog.str(asset, "a", Catalog.str(asset, "url", null));
                    final String checksum = Catalog.str(asset, "b", Catalog.str(asset, "checksum", ""));
                    if (url != null && !url.isEmpty()) {
                        files.put(file.getKey(), new Asset(url, checksum));
                    }
                }
            }
            if (files.isEmpty()) {
                continue; // Nothing to draw.
            }
            final Set<String> tags = new HashSet<>();
            if (o.has("h") && o.get("h").isJsonArray()) {
                for (JsonElement tag : o.getAsJsonArray("h")) {
                    if (tag.isJsonPrimitive()) {
                        tags.add(tag.getAsString().toUpperCase(Locale.ROOT));
                    }
                }
            }
            entries.put(id, new Entry(id, type == null ? "" : type.toUpperCase(Locale.ROOT), displayName(o, id), files,
                    Collections.unmodifiableSet(tags)));
        }
        return new EssentialCatalog(Collections.unmodifiableMap(entries));
    }

    /** English display name, else the first language that has one, else the id. */
    private static String displayName(JsonObject o, String fallback) {
        if (!o.has("c") || !o.get("c").isJsonObject()) {
            return fallback;
        }
        final JsonObject names = o.getAsJsonObject("c");
        final String en = Catalog.str(names, "en_us", Catalog.str(names, "en", null));
        if (en != null && !en.isEmpty()) {
            return en;
        }
        for (Map.Entry<String, JsonElement> e : names.entrySet()) {
            final String value = Catalog.str(names, e.getKey(), null);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return fallback;
    }

    private static void save(JsonArray cosmetics) {
        final Path file = cacheFile();
        try {
            Files.createDirectories(file.getParent());
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, cosmetics.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Clientmetics.LOGGER.warn("Couldn't save Essential's catalogue: {}", e.toString());
        }
    }

    /** Catalogue entries for a slot, for the wardrobe later; kept here so callers don't re-filter. */
    public List<Entry> ofType(String type) {
        final List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.type.equalsIgnoreCase(type)) {
                out.add(e);
            }
        }
        return out;
    }

}
