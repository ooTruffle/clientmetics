package com.ootruffle.clientmetics.asset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.model.Molang;
import java.io.BufferedReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The cosmetics catalogue, from the user's Lunar install or downloaded ({@link RemoteCatalog}):
 * <ul>
 *     <li>{@code cosmetics.json}: id, name, resource, category, geckolibCosmetic, animated</li>
 *     <li>{@code cosmetics/index}: CSV; column 8 names the shared model of non-GeckoLib items</li>
 *     <li>{@code cosmetics/indexes/hats.json, bodywear.json}: per-model body part and transformations</li>
 *     <li>{@code jit_index}: {@code path sha1 size timestamp} for every downloadable file</li>
 * </ul>
 * Loaded once, off the client thread.
 */
public final class Catalog {

    public static final class Entry {
        public final int id;
        public final String name;
        /** Path under {@code assets/lunar/}, without the {@code lunar:} namespace. */
        public final String resource;
        public final String category;
        public final boolean geckolib;
        /** Shared model name for non-GeckoLib hats and bodywear, or null. */
        public final String modelName;

        Entry(int id, String name, String resource, String category, boolean geckolib, String modelName) {
            this.id = id;
            this.name = name;
            this.resource = resource;
            this.category = category;
            this.geckolib = geckolib;
            this.modelName = modelName;
        }
    }

    /** A {@code hats.json} / {@code bodywear.json} entry. */
    public static final class ModelSettings {
        public final String name;
        /** HEAD or BODY. */
        public final String bodyPart;
        /** HATS or BODYWEAR, which is also the folder its OBJ is in. */
        public final String cosmeticType;
        public final JsonArray transformations;
        public final float minHeightOffset;
        public final float maxHeightOffset;

        ModelSettings(String name, JsonObject json) {
            this.name = name;
            this.bodyPart = str(json, "bodyPart", "HEAD");
            this.cosmeticType = str(json, "cosmeticType", "HATS");
            this.transformations = json.has("transformations") && json.get("transformations").isJsonArray()
                    ? json.getAsJsonArray("transformations") : new JsonArray();
            float min = 0;
            float max = 0;
            if (json.has("heightOffsetRange") && json.get("heightOffsetRange").isJsonArray()) {
                final JsonArray range = json.getAsJsonArray("heightOffsetRange");
                if (range.size() == 2) {
                    min = range.get(0).getAsFloat();
                    max = range.get(1).getAsFloat();
                }
            }
            this.minHeightOffset = min;
            this.maxHeightOffset = max;
        }

        /** {@code cosmetics/models/<hats|bodywear>/<name>/<name>.obj}. */
        public String objPath() {
            final String folder = "BODYWEAR".equalsIgnoreCase(cosmeticType) ? "bodywear" : "hats";
            return "cosmetics/models/" + folder + "/" + name + "/" + name + ".obj";
        }
    }

    /** A {@code jit_index} line. */
    public static final class FileInfo {
        public final String sha1;
        public final long size;

        FileInfo(String sha1, long size) {
            this.sha1 = sha1;
            this.size = size;
        }
    }

    private static volatile Catalog instance;

    private final Map<Integer, Entry> entries;
    private final Map<String, ModelSettings> models;
    private final Map<String, FileInfo> files;

    private Catalog(Map<Integer, Entry> entries, Map<String, ModelSettings> models, Map<String, FileInfo> files) {
        this.entries = entries;
        this.models = models;
        this.files = files;
    }

    /** The loaded catalogue, or null until {@link #load()} has run (or if there's no Lunar install). */
    public static Catalog get() {
        return instance;
    }

    public Entry entry(int id) {
        return entries.get(id);
    }

    public ModelSettings model(String name) {
        return name == null ? null : models.get(name);
    }

    /** {@code path} is relative to {@code assets/lunar/}. */
    public FileInfo file(String path) {
        return files.get("assets/lunar/" + path);
    }

    public java.util.Collection<Entry> entries() {
        return entries.values();
    }

    public int size() {
        return entries.size();
    }

    /**
     * Drops the catalogue (a few MB) after Lunar is turned off. Lunar cosmetics already loaded stop
     * being drawn and are freed by {@link MemoryJanitor} once they've gone unused. Does nothing if
     * Lunar was turned back on before this ran.
     */
    public static synchronized void unload() {
        if (instance != null && !Config.get().lunarOn()) {
            instance = null;
            Clientmetics.LOGGER.info("Lunar cosmetics turned off; unloaded the Lunar catalogue");
        }
    }

    /** Picks where the catalogue comes from (see {@code catalogueSource}) and loads it. Blocking; may use the network. */
    public static synchronized void load() {
        final String source = String.valueOf(Config.get().catalogueSource).toLowerCase(java.util.Locale.ROOT);
        final boolean useInstall = !source.equals("download") && LunarInstall.exists();
        if (useInstall) {
            Clientmetics.LOGGER.info("Reading the Lunar catalogue from the install at {}", LunarInstall.root());
            load(LunarInstall.textures());
            return;
        }
        if (source.equals("install")) {
            Clientmetics.LOGGER.warn("No Lunar install at {}; set lunarDirectory, or catalogueSource to auto, in config/clientmetics/config.json",
                    LunarInstall.root());
            return;
        }
        Clientmetics.LOGGER.info("Downloading the Lunar catalogue (no local install used)");
        if (RemoteCatalog.refresh()) {
            load(RemoteCatalog.dir());
        }
    }

    private static void load(Path dir) {
        try {
            final Map<String, String> modelNames = readIndexModelNames(dir.resolve("cosmetics").resolve("index"));
            final Map<Integer, Entry> entries = new HashMap<>();
            for (JsonElement e : readJson(dir.resolve("cosmetics.json")).getAsJsonArray()) {
                final JsonObject o = e.getAsJsonObject();
                final int id = o.get("id").getAsInt();
                final String resource = stripNamespace(str(o, "resource", ""));
                final String model = modelNames.get(String.valueOf(id));
                entries.put(id, new Entry(id, str(o, "name", "#" + id), resource, str(o, "category", ""),
                        o.has("geckolibCosmetic") && o.get("geckolibCosmetic").getAsBoolean(),
                        model == null || model.isEmpty() || model.equals("NONE") ? null : model));
            }

            final Map<String, ModelSettings> models = new HashMap<>();
            for (String file : new String[]{"hats.json", "bodywear.json"}) {
                final Path p = dir.resolve("cosmetics").resolve("indexes").resolve(file);
                if (Files.isRegularFile(p)) {
                    for (Map.Entry<String, JsonElement> m : readJson(p).getAsJsonObject().entrySet()) {
                        if (m.getValue().isJsonObject()) {
                            models.put(m.getKey(), new ModelSettings(m.getKey(), m.getValue().getAsJsonObject()));
                        }
                    }
                }
            }

            final Map<String, FileInfo> files = new HashMap<>();
            final Path jit = dir.resolve("jit_index");
            if (Files.isRegularFile(jit)) {
                for (String line : Files.readAllLines(jit, StandardCharsets.UTF_8)) {
                    final String[] parts = line.trim().split(" ");
                    if (parts.length >= 3) {
                        try {
                            files.put(parts[0], new FileInfo(parts[1], Long.parseLong(parts[2])));
                        } catch (NumberFormatException ignored) {
                            // Skip a malformed line.
                        }
                    }
                }
            }
            final Path functions = dir.resolve("cosmetics").resolve("functions.molang");
            if (Files.isRegularFile(functions)) {
                final int loaded = Molang.registerFunctions(new String(Files.readAllBytes(functions), StandardCharsets.UTF_8));
                Clientmetics.LOGGER.info("Loaded {} Molang helper functions", loaded);
            }
            instance = new Catalog(Collections.unmodifiableMap(entries), Collections.unmodifiableMap(models),
                    Collections.unmodifiableMap(files));
            Clientmetics.LOGGER.info("Loaded Lunar catalogue: {} cosmetics, {} models, {} files", entries.size(), models.size(), files.size());
        } catch (Exception e) {
            Clientmetics.LOGGER.error("Couldn't read the Lunar catalogue in {}", dir, e);
        }
    }

    /** {@code cosmetics/index}: {@code id,,,name,,bool,category,model,path,geckolib,tags,colors}. */
    private static Map<String, String> readIndexModelNames(Path index) throws Exception {
        final Map<String, String> out = new HashMap<>();
        if (!Files.isRegularFile(index)) {
            return out;
        }
        try (BufferedReader r = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                final String[] cols = line.split(",", -1);
                if (cols.length > 7) {
                    out.put(cols[0], cols[7]);
                }
            }
        }
        return out;
    }

    static JsonElement readJson(Path p) throws Exception {
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            return new JsonParser().parse(r);
        }
    }

    public static String stripNamespace(String resource) {
        final int colon = resource.indexOf(':');
        return colon >= 0 ? resource.substring(colon + 1) : resource;
    }

    static String str(JsonObject o, String key, String def) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

}
