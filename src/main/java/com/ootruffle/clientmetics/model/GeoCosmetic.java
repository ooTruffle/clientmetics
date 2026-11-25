package com.ootruffle.clientmetics.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ootruffle.clientmetics.asset.AssetFiles;
import com.ootruffle.clientmetics.asset.Catalog;
import com.ootruffle.clientmetics.asset.DawnCatalog;
import com.ootruffle.clientmetics.asset.DawnFiles;
import com.ootruffle.clientmetics.asset.EssentialCatalog;
import com.ootruffle.clientmetics.asset.EssentialFiles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A Lunar GeckoLib cosmetic: its {@code .gek.json} (type, attached bone, model, texture, animation
 * and state machine) plus the parsed model and animations. Loaded off-thread.
 * <p>
 * Dawn cosmetics ({@link #loadDawn}) are the same kind of model without a {@code .gek.json}: the
 * files are found by slug, bones named {@code anchor_*} follow the player's parts, and an optional
 * {@code .meta.json} says how armour affects them.
 */
public final class GeoCosmetic {

    /** A {@code molang} state-machine state: plays {@code anim} while {@code playsWhen} is true. */
    private static final class State {
        final String anim;
        final Molang.Expr playsWhen;

        State(String anim, Molang.Expr playsWhen) {
            this.anim = anim;
            this.playsWhen = playsWhen;
        }
    }

    /** hat, pet, backpack, suits, shoes, float, wristwear, ... */
    public final String type;
    /** HEAD, SHOULDER or NONE. */
    public final String attachedBone;
    public final String texturePath;
    /** One model, or several picked by a Molang condition (e.g. slim and wide arms). */
    private final List<GeoModel> models;
    private final List<Molang.Expr> modelConditions;
    private final Map<String, GeoAnimation> animations;
    private final List<State> states;
    /** {@code cosmetic_options} defaults, e.g. {@code option.size}. */
    private final Map<String, Double> options;
    /** Dawn: only {@code anchor_head}, {@code anchor_body}... bones follow the player (a pet's own "head" bone doesn't). */
    public final boolean anchored;
    /** Dawn {@code armorConstraints}: armour slots (0 boots .. 3 helmet) that hide this cosmetic while worn. */
    public final int[] hiddenByArmor;
    /** Dawn {@code transforms}: {@code {armour slot, x, y, z}} offsets, in pixels, applied while that slot is worn. */
    public final float[][] armorOffsets;
    /**
     * Essential {@code EXTERNAL_HIDDEN_BONE}: another cosmetic's id to the bone names of it to hide
     * while this one is worn (a hoodie hides a skirt's {@code body_outer}).
     */
    public volatile Map<String, java.util.Set<String>> hidesBonesOf = Collections.emptyMap();
    /**
     * Essential {@code COSMETIC_BONE_HIDING}: another cosmetic's id to the body groups of this one
     * ({@code head}, {@code arms}, {@code body}, {@code legs}) to hide while that one is worn (a maid
     * outfit hides its body and arms under a hoodie, keeping its skirt).
     */
    public volatile Map<String, java.util.Set<String>> hiddenUnder = Collections.emptyMap();
    /**
     * Essential: where this cosmetic's torso is, for drawing it over the Feminine Gender Mod's
     * breasts: {@code {front face u, front face v, inflate, texture width, texture height}} of each
     * body-sized (8x12x4) cube, innermost first (a solid top and its overlay layer), or null if none.
     */
    public volatile float[][] torsoFront;
    /** Essential: outer skin layers this cosmetic covers ({@link SkinCover} bits), for wide and slim arms. */
    public volatile int skinCoverSteve;
    public volatile int skinCoverAlex;
    /** Essential: the outer-layer pixels it hides ({@link SkinCover.Mask#hidden}), for wide and slim arms, or null. */
    public volatile long[] skinMaskSteve;
    public volatile long[] skinMaskAlex;

    private GeoCosmetic(String type, String attachedBone, String texturePath, List<GeoModel> models, List<Molang.Expr> modelConditions,
                        Map<String, GeoAnimation> animations, List<State> states, Map<String, Double> options) {
        this(type, attachedBone, texturePath, models, modelConditions, animations, states, options, false, new int[0], new float[0][]);
    }

    private GeoCosmetic(String type, String attachedBone, String texturePath, List<GeoModel> models, List<Molang.Expr> modelConditions,
                        Map<String, GeoAnimation> animations, List<State> states, Map<String, Double> options,
                        boolean anchored, int[] hiddenByArmor, float[][] armorOffsets) {
        this.anchored = anchored;
        this.hiddenByArmor = hiddenByArmor;
        this.armorOffsets = armorOffsets;
        this.type = type;
        this.attachedBone = attachedBone;
        this.texturePath = texturePath;
        this.models = models;
        this.modelConditions = modelConditions;
        this.animations = animations;
        this.states = states;
        this.options = options;
    }

    /** The key {@link #load} takes for a Dawn cosmetic. */
    public static String dawnKey(String slug) {
        return DawnFiles.PREFIX + slug;
    }

    /** The key {@link #load} takes for an Essential cosmetic: {@code essential:<cosmetic id>/<variant>}. */
    public static String essentialKey(String id, String variant) {
        return EssentialFiles.PREFIX + id + "/" + (variant == null ? "" : variant);
    }

    public static GeoCosmetic load(String gekPath) throws Exception {
        if (gekPath.startsWith(DawnFiles.PREFIX)) {
            return loadDawn(gekPath.substring(DawnFiles.PREFIX.length()));
        }
        if (gekPath.startsWith(EssentialFiles.PREFIX)) {
            return loadEssential(gekPath.substring(EssentialFiles.PREFIX.length()));
        }
        final JsonObject gek = json(gekPath);
        // "model" is a path, or an object of path -> Molang condition ({"...geo.json": "!q.is_slim_model", ...}).
        final List<GeoModel> models = new ArrayList<>();
        final List<Molang.Expr> modelConditions = new ArrayList<>();
        final JsonElement modelJson = gek.get("model");
        if (modelJson != null && modelJson.isJsonObject()) {
            for (Map.Entry<String, JsonElement> m : modelJson.getAsJsonObject().entrySet()) {
                models.add(GeoModel.parse(json(Catalog.stripNamespace(m.getKey()))));
                final JsonElement cond = m.getValue();
                modelConditions.add(cond.isJsonPrimitive() && cond.getAsJsonPrimitive().isString()
                        ? Molang.compile(cond.getAsString()) : Molang.constant(1));
            }
        } else {
            models.add(GeoModel.parse(json(Catalog.stripNamespace(modelJson.getAsString()))));
            modelConditions.add(Molang.constant(1));
        }
        if (models.isEmpty()) {
            throw new IllegalArgumentException("no model in " + gekPath);
        }

        Map<String, GeoAnimation> animations = Collections.emptyMap();
        if (gek.has("animation") && gek.get("animation").isJsonPrimitive()) {
            final String animPath = Catalog.stripNamespace(gek.get("animation").getAsString());
            if (AssetFiles.exists(animPath)) {
                animations = GeoAnimation.parseFile(json(animPath));
            }
        }

        final List<State> states = new ArrayList<>();
        final JsonObject machine = gek.has("state_machine") && gek.get("state_machine").isJsonObject()
                ? gek.getAsJsonObject("state_machine") : new JsonObject();
        if (machine.has("anim")) {
            states.add(new State(machine.get("anim").getAsString(), Molang.constant(1)));
        }
        if (machine.has("controllers") && machine.get("controllers").isJsonArray()) {
            final JsonArray controllers = machine.getAsJsonArray("controllers");
            if (controllers.size() > 0 && controllers.get(0).isJsonObject()) {
                final JsonObject controller = controllers.get(0).getAsJsonObject();
                if (controller.has("states") && controller.get("states").isJsonArray()) {
                    for (JsonElement s : controller.getAsJsonArray("states")) {
                        final JsonObject state = s.getAsJsonObject();
                        if (!state.has("anim")) {
                            continue;
                        }
                        final JsonElement when = state.get("plays_when");
                        final Molang.Expr cond = when == null ? Molang.constant(1)
                                : when.getAsJsonPrimitive().isNumber() ? Molang.constant(when.getAsDouble())
                                : Molang.compile(when.getAsString());
                        states.add(new State(state.get("anim").getAsString(), cond));
                    }
                }
            }
        }
        if (states.isEmpty() && !animations.isEmpty()) {
            states.add(new State(animations.keySet().iterator().next(), Molang.constant(1)));
        }

        final Map<String, Double> options = new java.util.HashMap<>();
        if (gek.has("cosmetic_options") && gek.get("cosmetic_options").isJsonArray()) {
            for (JsonElement o : gek.getAsJsonArray("cosmetic_options")) {
                final JsonObject opt = o.getAsJsonObject();
                if (opt.has("molang_query") && opt.has("default")) {
                    options.put(opt.get("molang_query").getAsString(), opt.get("default").getAsDouble());
                }
            }
        }

        return new GeoCosmetic(
                gek.has("type") ? gek.get("type").getAsString() : "",
                gek.has("attached_bone") ? gek.get("attached_bone").getAsString() : "NONE",
                Catalog.stripNamespace(gek.get("texture").getAsString()),
                models, modelConditions, animations, states, options);
    }

    private static GeoCosmetic loadDawn(String slug) throws Exception {
        final DawnCatalog catalog = DawnCatalog.get();
        final DawnCatalog.Entry entry = catalog == null ? null : catalog.entry(slug);
        if (entry == null || entry.geo == null || !entry.hasReadableTexture()) {
            throw new IllegalArgumentException("no drawable Dawn model for " + slug);
        }
        final List<GeoModel> models = Collections.singletonList(GeoModel.parse(json(DawnFiles.PREFIX + entry.geo)));
        final List<Molang.Expr> conditions = Collections.singletonList(Molang.constant(1));

        // Clips are named animation.<slug>.idle / walk / swim / fly; the most specific one that applies plays.
        Map<String, GeoAnimation> animations = Collections.emptyMap();
        final List<State> states = new ArrayList<>();
        if (entry.animation != null) {
            animations = GeoAnimation.parseFile(json(DawnFiles.PREFIX + entry.animation));
            final String[][] clips = {{".swim", "query.is_swimming"}, {".walk", "query.is_moving"}, {".idle", null}};
            for (String[] clip : clips) {
                for (String name : animations.keySet()) {
                    if (name.endsWith(clip[0])) {
                        states.add(new State(name, clip[1] == null ? Molang.constant(1) : Molang.compile(clip[1])));
                        break;
                    }
                }
            }
            if (states.isEmpty() && !animations.isEmpty()) {
                states.add(new State(animations.keySet().iterator().next(), Molang.constant(1)));
            }
        }

        final List<Integer> hidden = new ArrayList<>();
        final List<float[]> offsets = new ArrayList<>();
        if (entry.meta != null) {
            final JsonObject meta = json(DawnFiles.PREFIX + entry.meta);
            for (JsonElement c : array(meta, "armorConstraints")) {
                final int slot = c.isJsonObject() ? armorSlot(c.getAsJsonObject()) : -1;
                if (slot >= 0) {
                    hidden.add(slot);
                }
            }
            for (JsonElement t : array(meta, "transforms")) {
                if (!t.isJsonObject()) {
                    continue;
                }
                final JsonObject transform = t.getAsJsonObject();
                final int slot = armorSlot(transform);
                if (slot >= 0 && transform.has("translate") && transform.get("translate").isJsonObject()) {
                    final JsonObject by = transform.getAsJsonObject("translate");
                    offsets.add(new float[]{slot, number(by, "x"), number(by, "y"), number(by, "z")});
                }
            }
        }
        final int[] hiddenByArmor = new int[hidden.size()];
        for (int i = 0; i < hiddenByArmor.length; i++) {
            hiddenByArmor[i] = hidden.get(i);
        }
        return new GeoCosmetic(entry.type, "NONE", DawnFiles.PREFIX + entry.texture, models, conditions, animations, states,
                new java.util.HashMap<>(), true, hiddenByArmor, offsets.toArray(new float[0][]));
    }

    private static GeoCosmetic loadEssential(String key) throws Exception {
        final int slash = key.indexOf('/');
        final String id = slash < 0 ? key : key.substring(0, slash);
        final String variant = slash < 0 ? "" : key.substring(slash + 1);
        final EssentialCatalog catalog = EssentialCatalog.get();
        final EssentialCatalog.Entry entry = catalog == null ? null : catalog.entry(id);
        if (entry == null) {
            throw new IllegalArgumentException("no Essential catalogue entry for " + id);
        }
        // Each file is listed separately in the catalogue, so these are direct downloads.
        final String texture = EssentialFiles.texturePath(id, variant);
        final List<String> geometries = entry.geometries();
        if (geometries.isEmpty() || texture == null) {
            throw new IllegalArgumentException("Essential cosmetic " + id + " has no geometry or texture");
        }

        // A cosmetic with a slim-arm model ships geometry.steve.json and geometry.alex.json; pick by
        // the wearer's skin instead of always taking whichever sorts first.
        final List<GeoModel> models = new ArrayList<>();
        final List<Molang.Expr> conditions = new ArrayList<>();
        for (String path : geometries) {
            models.add(GeoModel.parse(json(EssentialFiles.PREFIX + id + "/" + path)));
            final String lower = path.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("alex")) {
                conditions.add(Molang.compile("query.is_slim_model"));
            } else if (lower.contains("steve")) {
                conditions.add(Molang.compile("!query.is_slim_model"));
            } else {
                conditions.add(Molang.constant(1));
            }
        }

        // Before the texture is first decoded, so a strip of frames is split instead of squeezed.
        EssentialFiles.registerFrameAspect(texture, models.get(0).textureWidth, models.get(0).textureHeight);

        Map<String, GeoAnimation> animations = Collections.emptyMap();
        final List<State> states = new ArrayList<>();
        final String animation = entry.animation() == null ? null
                : EssentialFiles.PREFIX + id + "/" + entry.animation();
        if (animation != null) {
            final JsonObject file = json(animation);
            animations = GeoAnimation.parseFile(file);
            final String idle = essentialIdle(file, animations);
            if (idle != null) {
                states.add(new State(idle, Molang.constant(1)));
            }
        }

        // Bone names decide what follows the player (see CosmeticRenderer.partFor), which is how
        // Essential drives its own models. settings.json says when armour hides the cosmetic.
        final GeoCosmetic geo = new GeoCosmetic(entry.type, "NONE", texture, models, conditions, animations, states,
                new java.util.HashMap<>(), false, hiddenSlots(entry), new float[0][]);
        // Which outer skin layers it covers; a cosmetic without a slim mask uses the wide one.
        final String steve = entry.files.containsKey("skin_mask.steve.png") ? "skin_mask.steve.png" : null;
        final String alex = entry.files.containsKey("skin_mask.alex.png") ? "skin_mask.alex.png" : steve;
        final SkinCover.Mask steveMask = steve == null ? SkinCover.NONE : SkinCover.read(EssentialFiles.PREFIX + id + "/" + steve);
        final SkinCover.Mask alexMask = alex == null ? SkinCover.NONE : alex.equals(steve) ? steveMask
                : SkinCover.read(EssentialFiles.PREFIX + id + "/" + alex);
        geo.skinCoverSteve = steveMask.parts;
        geo.skinCoverAlex = alexMask.parts;
        geo.skinMaskSteve = steveMask.hidden;
        geo.skinMaskAlex = alexMask.hidden;
        readBoneHiding(entry, geo);
        geo.torsoFront = torsoFront(json(EssentialFiles.PREFIX + id + "/" + geometries.get(0)));
        return geo;
    }

    /**
     * The animation Essential plays while nothing else is going on: the {@code IDLE} trigger of
     * {@code animations.json}, or what it hands over to when it finishes (a one-off intro followed by
     * a loop). Emote and random-tick triggers aren't played. Falls back to the first clip.
     */
    private static String essentialIdle(JsonObject file, Map<String, GeoAnimation> animations) {
        final JsonElement triggers = file.get("triggers");
        if (triggers != null && triggers.isJsonArray()) {
            for (JsonElement t : triggers.getAsJsonArray()) {
                if (!t.isJsonObject() || !"IDLE".equalsIgnoreCase(str(t.getAsJsonObject(), "type"))) {
                    continue;
                }
                final JsonObject trigger = t.getAsJsonObject();
                final JsonElement then = trigger.get("on_complete");
                if (then != null && then.isJsonObject() && animations.containsKey(str(then.getAsJsonObject(), "name"))) {
                    return str(then.getAsJsonObject(), "name");
                }
                if (animations.containsKey(str(trigger, "name"))) {
                    return str(trigger, "name");
                }
            }
        }
        return animations.isEmpty() ? null : animations.keySet().iterator().next();
    }

    /**
     * Armour slots (0 boots .. 3 helmet) that hide this cosmetic, from the {@code ARMOR_HANDLING}
     * property in the cosmetic's {@code settings.json} ({@code {"head": true}} and friends).
     */
    /**
     * The body-sized cubes under the {@code body} bone, as {@link #torsoFront}. Each one's front
     * face is the chest: {@code (u + 4, v + 4)} for box UV, the {@code north} face for per-face UV.
     */
    static float[][] torsoFront(JsonObject root) {
        final com.google.gson.JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.size() == 0) {
            return null;
        }
        final JsonObject geo = geometries.get(0).getAsJsonObject();
        final JsonObject desc = geo.has("description") ? geo.getAsJsonObject("description") : new JsonObject();
        final float texW = desc.has("texture_width") ? desc.get("texture_width").getAsFloat() : 64;
        final float texH = desc.has("texture_height") ? desc.get("texture_height").getAsFloat() : 64;
        if (!geo.has("bones")) {
            return null;
        }
        // Bones under "body", following parents.
        final Map<String, String> parents = new java.util.HashMap<>();
        for (JsonElement e : geo.getAsJsonArray("bones")) {
            final JsonObject b = e.getAsJsonObject();
            parents.put(str(b, "name"), str(b, "parent"));
        }
        final List<float[]> found = new ArrayList<>();
        for (JsonElement e : geo.getAsJsonArray("bones")) {
            final JsonObject b = e.getAsJsonObject();
            if (!underBody(str(b, "name"), parents) || !b.has("cubes")) {
                continue;
            }
            for (JsonElement c : b.getAsJsonArray("cubes")) {
                final JsonObject cube = c.getAsJsonObject();
                final com.google.gson.JsonArray size = cube.has("size") ? cube.getAsJsonArray("size") : null;
                if (size == null || size.size() != 3 || Math.abs(size.get(0).getAsFloat() - 8) > 0.01f
                        || Math.abs(size.get(1).getAsFloat() - 12) > 0.01f || Math.abs(size.get(2).getAsFloat() - 4) > 0.01f
                        || cube.has("rotation")) {
                    continue;
                }
                final float inflate = cube.has("inflate") ? cube.get("inflate").getAsFloat() : 0;
                final JsonElement uv = cube.get("uv");
                float u;
                float v;
                if (uv != null && uv.isJsonArray() && uv.getAsJsonArray().size() >= 2) {
                    u = uv.getAsJsonArray().get(0).getAsFloat() + 4;
                    v = uv.getAsJsonArray().get(1).getAsFloat() + 4;
                } else if (uv != null && uv.isJsonObject() && uv.getAsJsonObject().has("north")
                        && uv.getAsJsonObject().getAsJsonObject("north").has("uv")) {
                    final com.google.gson.JsonArray north = uv.getAsJsonObject().getAsJsonObject("north").getAsJsonArray("uv");
                    u = north.get(0).getAsFloat();
                    v = north.get(1).getAsFloat();
                } else {
                    continue;
                }
                found.add(new float[]{u, v, inflate, texW, texH});
            }
        }
        found.sort((a, b) -> Float.compare(a[2], b[2]));
        return found.isEmpty() ? null : found.toArray(new float[0][]);
    }

    private static boolean underBody(String bone, Map<String, String> parents) {
        for (int i = 0; bone != null && i < 32; i++) {
            if ("body".equalsIgnoreCase(bone)) {
                return true;
            }
            bone = parents.get(bone);
        }
        return false;
    }

    /** Reads the {@code EXTERNAL_HIDDEN_BONE} and {@code COSMETIC_BONE_HIDING} rules from settings.json. */
    private static void readBoneHiding(EssentialCatalog.Entry entry, GeoCosmetic geo) {
        final String settingsPath = entry.settings();
        if (settingsPath == null) {
            return;
        }
        final Map<String, java.util.Set<String>> external = new java.util.HashMap<>();
        final Map<String, java.util.Set<String>> under = new java.util.HashMap<>();
        try {
            final JsonElement parsed = new JsonParser().parse(new String(
                    AssetFiles.read(EssentialFiles.PREFIX + entry.id + "/" + settingsPath), StandardCharsets.UTF_8));
            if (!parsed.isJsonArray()) {
                return;
            }
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonObject property = element.getAsJsonObject();
                final String type = str(property, "type");
                final String other = str(property, "id");
                final JsonElement enabled = property.get("enabled");
                final JsonElement data = property.get("data");
                if (other == null || data == null || !data.isJsonObject()
                        || enabled != null && enabled.isJsonPrimitive() && !enabled.getAsBoolean()) {
                    continue;
                }
                final Map<String, java.util.Set<String>> target = "EXTERNAL_HIDDEN_BONE".equalsIgnoreCase(type) ? external
                        : "COSMETIC_BONE_HIDING".equalsIgnoreCase(type) ? under : null;
                if (target == null) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> bone : data.getAsJsonObject().entrySet()) {
                    if (bone.getValue().isJsonPrimitive() && bone.getValue().getAsBoolean()) {
                        target.computeIfAbsent(other, k -> new java.util.HashSet<>()).add(bone.getKey().toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
        } catch (Exception e) {
            return; // No rules: everything is drawn.
        }
        geo.hidesBonesOf = external;
        geo.hiddenUnder = under;
    }

    private static int[] hiddenSlots(EssentialCatalog.Entry entry) {
        final String settingsPath = entry.settings();
        if (settingsPath == null) {
            return new int[0];
        }
        try {
            final JsonElement parsed = new JsonParser().parse(new String(
                    AssetFiles.read(EssentialFiles.PREFIX + entry.id + "/" + settingsPath), StandardCharsets.UTF_8));
            if (!parsed.isJsonArray()) {
                return new int[0];
            }
            final List<Integer> slots = new ArrayList<>();
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonObject property = element.getAsJsonObject();
                if (!"ARMOR_HANDLING".equalsIgnoreCase(str(property, "type"))) {
                    continue;
                }
                final JsonElement enabled = property.get("enabled");
                if (enabled != null && enabled.isJsonPrimitive() && !enabled.getAsBoolean()) {
                    continue;
                }
                final JsonElement data = property.get("data");
                if (data == null || !data.isJsonObject()) {
                    continue;
                }
                final JsonObject d = data.getAsJsonObject();
                for (String[] slot : new String[][]{{"head", "3"}, {"chest", "2"}, {"body", "2"}, {"legs", "1"}, {"feet", "0"}}) {
                    if (flag(d, slot[0])) {
                        final int index = Integer.parseInt(slot[1]);
                        if (!slots.contains(index)) {
                            slots.add(index);
                        }
                    }
                }
            }
            final int[] out = new int[slots.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = slots.get(i);
            }
            return out;
        } catch (Exception e) {
            return new int[0]; // A settings file we can't read just means no armour hiding.
        }
    }

    private static boolean flag(JsonObject o, String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static String str(JsonObject o, String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static JsonArray array(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new JsonArray();
    }

    private static float number(JsonObject json, String key) {
        final JsonElement e = json.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsFloat() : 0;
    }

    /** The armour inventory index for a meta entry's {@code slot}, or -1. */
    private static int armorSlot(JsonObject json) {
        final JsonElement slot = json.get("slot");
        if (slot == null || !slot.isJsonPrimitive()) {
            return -1;
        }
        switch (slot.getAsString()) {
            case "helmet":
                return 3;
            case "chestplate":
                return 2;
            case "leggings":
                return 1;
            case "boots":
                return 0;
            default:
                return -1; // "elytra" doesn't exist in 1.8.9.
        }
    }

    /** The model whose condition holds (the first one if none do). */
    public GeoModel model(Molang.Scope scope) {
        for (int i = 0; i < models.size(); i++) {
            if (modelConditions.get(i).eval(scope) != 0) {
                return models.get(i);
            }
        }
        return models.get(0);
    }

    /** Frees every model's display lists (render thread). */
    public void delete() {
        for (GeoModel m : models) {
            m.delete();
        }
    }

    /** Sets option defaults (e.g. wing size) in the scope. */
    public void applyOptions(Molang.Scope scope) {
        for (Map.Entry<String, Double> o : options.entrySet()) {
            scope.set(o.getKey(), o.getValue());
        }
    }

    /** The animation the state machine picks right now, or null. */
    /** Whether no model has anything to draw (particle-only effects). */
    public boolean isEmptyModel() {
        for (GeoModel model : models) {
            if (!model.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public GeoAnimation currentAnimation(Molang.Scope scope) {
        for (State state : states) {
            if (state.playsWhen.eval(scope) != 0) {
                return animations.get(state.anim);
            }
        }
        return null;
    }

    private static JsonObject json(String path) throws Exception {
        return new JsonParser().parse(new String(AssetFiles.read(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }

}
