package com.ootruffle.clientmetics.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.asset.AssetFiles;
import com.ootruffle.clientmetics.asset.Assets;
import com.ootruffle.clientmetics.asset.Catalog;
import com.ootruffle.clientmetics.asset.DawnCatalog;
import com.ootruffle.clientmetics.asset.DawnFiles;
import com.ootruffle.clientmetics.asset.EssentialCatalog;
import com.ootruffle.clientmetics.asset.EssentialFiles;
import com.ootruffle.clientmetics.asset.Textures;
import com.ootruffle.clientmetics.cosmetic.EquippedCosmetic;
import com.ootruffle.clientmetics.cosmetic.EssentialSnapshot;
import com.ootruffle.clientmetics.cosmetic.Recolours;
import com.ootruffle.clientmetics.model.GeoAnimation;
import com.ootruffle.clientmetics.model.GeoCosmetic;
import com.ootruffle.clientmetics.model.GeoModel;
import com.ootruffle.clientmetics.model.Molang;
import com.ootruffle.clientmetics.model.ObjModel;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

/**
 * Draws Lunar cosmetics on a player, added to every {@link RenderPlayer} as a layer:
 * <ul>
 *     <li><b>OBJ hats and bodywear</b> attach to the head or body bone, like vanilla's head layer,
 *     then apply the model's {@code player} transformations from {@code hats.json} /
 *     {@code bodywear.json} in order. Lunar authors these in that bone's space: blocks, Y down.</li>
 *     <li><b>GeckoLib cosmetics</b> are drawn in GeckoLib's space (feet at the origin, Y up, pixels),
 *     made by undoing the player renderer's flip. HEAD and SHOULDER items follow the head or body;
 *     bones named after a player part (head, body, arms, legs) follow that part.</li>
 * </ul>
 * Dawn cosmetics are GeckoLib-style models too, drawn the same way, except that only bones named
 * {@code anchor_head}, {@code anchor_body}, {@code anchor_leftarm}... follow the player's parts.
 * <p>
 * Cloaks aren't drawn here: they replace the vanilla cape texture (see {@link Cloaks}).
 */
public final class CosmeticRenderer implements LayerRenderer<AbstractClientPlayer> {

    /** The player renderer's model-space origin sits this far above the feet. */
    private static final float MODEL_ORIGIN = 1.5078125F;
    private static final float SNEAK_DROP = 0.2F;
    /**
     * Blocks per unit of {@code hat_height_offset}. Lunar's own value isn't documented; the
     * models' {@code heightOffsetRange} (about -1 to 0.5) suggests a small step, so 1/8 block.
     */
    private static final float HEIGHT_OFFSET_UNIT = 0.125F;

    private enum Part {
        HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG
    }

    private final RenderPlayer renderer;

    public CosmeticRenderer(RenderPlayer renderer) {
        this.renderer = renderer;
    }

    /** Starts loading what an outfit needs, so it's ready by the time the player is drawn. Any thread. */
    public static void preload(List<EquippedCosmetic> outfit) {
        final Catalog catalog = Catalog.get();
        if (catalog == null) {
            return;
        }
        for (EquippedCosmetic c : outfit) {
            final Catalog.Entry entry = catalog.entry(c.id);
            if (entry == null) {
                continue;
            }
            if ("cloak".equals(entry.category)) {
                Textures.preload(entry.resource, Textures.Kind.CLOAK);
            } else if (entry.geckolib) {
                final GeoCosmetic geo = geo(entry);
                if (geo != null) {
                    Textures.preload(geo.texturePath, Textures.Kind.MODEL);
                }
            } else {
                final Catalog.ModelSettings settings = catalog.model(entry.modelName);
                if (settings != null) {
                    obj(settings);
                    Textures.preload(entry.resource, Textures.Kind.MODEL);
                }
            }
        }
    }

    /** Starts loading what a Dawn outfit (cosmetic slugs) needs. Any thread. */
    public static void preloadDawn(Collection<String> slugs) {
        final DawnCatalog catalog = DawnCatalog.get();
        if (catalog == null) {
            return;
        }
        for (String slug : slugs) {
            final DawnCatalog.Entry entry = catalog.entry(slug);
            if (entry == null || !entry.hasReadableTexture()) {
                continue;
            }
            if ("cape".equals(entry.type)) {
                Textures.preload(DawnFiles.PREFIX + entry.texture, Textures.Kind.CLOAK);
            } else if (entry.geo != null && dawnTypeShown(entry.type, Config.get())) {
                final GeoCosmetic geo = Assets.get("geo", GeoCosmetic.dawnKey(slug), GeoCosmetic::load);
                if (geo != null) {
                    Textures.preload(geo.texturePath, Textures.Kind.MODEL);
                }
            }
        }
    }

    /** Whether this Dawn type is one this mod draws as a model, and its setting is on. */
    private static boolean dawnTypeShown(String type, Config config) {
        switch (type) {
            case "hat":
            case "face":
                return config.showHats;
            case "back":
            case "arm":
            case "boot":
                return config.showBodywear;
            case "wing":
            case "pet":
            case "aura":
                return config.showGeckolib;
            default:
                return false; // Cloaks are drawn by Cloaks; shields, trims and emotes aren't supported.
        }
    }

    /** Whether this Essential slot is drawn as a model, and its setting is on. Capes go through {@link Cloaks}. */
    private static boolean essentialTypeShown(String type, Config config) {
        if (type == null) {
            return false;
        }
        switch (type) {
            case "HAT":
            case "HEAD":
            case "FACE":
            case "EARS":
                return config.showHats;
            case "WINGS":
            case "PET":
            case "TAIL":
            case "EFFECT":
                return config.showGeckolib;
            case "SUITS":
            case "TOP":
            case "SKIRT":
            case "PANTS":
            case "SHOES":
            case "BACK":
            case "ARMS":
            case "SHOULDERS":
            case "FULL_BODY":
            case "ACCESSORY":
                return config.showBodywear;
            // Capes are drawn by the vanilla cape layer with Essential's texture (see Cloaks), so
            // they swing like capes and cape mods draw them; only their files are loaded here.
            case "CAPE":
                return config.showCloaks;
            default:
                return false; // Emotes and icons aren't worn.
        }
    }

    /**
     * Whether an Essential cosmetic this player is wearing covers this outer skin layer part (at
     * least {@code SkinCover.THRESHOLD} of it, see {@link com.ootruffle.clientmetics.model.SkinCover}). Only
     * cosmetics that are actually drawn count: their type is shown, they've loaded, and armour
     * isn't hiding them. Render thread.
     */
    public static boolean coversSkinLayer(AbstractClientPlayer player, int bit) {
        if (bit == 0) {
            return false;
        }
        final boolean slim = "slim".equals(player.getSkinType());
        for (GeoCosmetic geo : maskingCosmetics(player)) {
            if (((slim ? geo.skinCoverAlex : geo.skinCoverSteve) & bit) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The outer skin layer pixels hidden by the Essential cosmetics this player wears, as
     * {@link com.ootruffle.clientmetics.model.SkinCover} rows, or null if none are. The same
     * cosmetics count as for {@link #coversSkinLayer}. Render thread.
     */
    public static long[] skinMask(AbstractClientPlayer player) {
        final boolean slim = "slim".equals(player.getSkinType());
        long[] hidden = null;
        for (GeoCosmetic geo : maskingCosmetics(player)) {
            hidden = com.ootruffle.clientmetics.model.SkinCover.union(hidden, slim ? geo.skinMaskAlex : geo.skinMaskSteve);
        }
        return hidden;
    }

    /** The worn Essential cosmetics that are drawn and ship a skin mask: type shown, loaded, not hidden by armour. */
    private static List<GeoCosmetic> maskingCosmetics(AbstractClientPlayer player) {
        final Config config = Config.get();
        if (!config.enabled || !config.essentialOn() || player.isInvisible()) {
            return java.util.Collections.emptyList();
        }
        final EssentialSnapshot snapshot = PreviewRenderer.essentialOutfitFor(player.getUniqueID());
        final EssentialCatalog catalog = snapshot == null ? null : EssentialCatalog.get();
        if (catalog == null) {
            return java.util.Collections.emptyList();
        }
        List<GeoCosmetic> out = null;
        for (String cosmeticId : snapshot.equipped.values()) {
            final EssentialCatalog.Entry entry = catalog.entry(cosmeticId);
            if (!EssentialCatalog.isWearable(entry) || "CAPE".equals(entry.type) || !essentialTypeShown(entry.type, config)) {
                continue;
            }
            final GeoCosmetic geo = Assets.get("geo", GeoCosmetic.essentialKey(entry.id, snapshot.variantOf(entry.id)),
                    GeoCosmetic::load);
            if (geo == null || (geo.skinMaskSteve == null && geo.skinMaskAlex == null)
                    || hiddenByArmor(player, geo.hiddenByArmor)) {
                continue;
            }
            if (out == null) {
                out = new java.util.ArrayList<>(2);
            }
            out.add(geo);
        }
        return out == null ? java.util.Collections.<GeoCosmetic>emptyList() : out;
    }

    /**
     * The worn Essential cosmetic to draw over the Feminine Gender Mod's breasts: the one that
     * covers the torso (see {@link #coversSkinLayer}) and has a body-sized cube, outermost first.
     * Null if none (or one covers the torso but has no such cube). Render thread.
     */
    public static GeoCosmetic torsoCover(AbstractClientPlayer player) {
        final Config config = Config.get();
        final EssentialSnapshot snapshot = config.enabled && config.essentialOn()
                ? PreviewRenderer.essentialOutfitFor(player.getUniqueID()) : null;
        final EssentialCatalog catalog = snapshot == null ? null : EssentialCatalog.get();
        if (catalog == null) {
            return null;
        }
        final boolean slim = "slim".equals(player.getSkinType());
        GeoCosmetic best = null;
        for (String cosmeticId : snapshot.equipped.values()) {
            final EssentialCatalog.Entry entry = catalog.entry(cosmeticId);
            if (!EssentialCatalog.isWearable(entry) || "CAPE".equals(entry.type) || !essentialTypeShown(entry.type, config)) {
                continue;
            }
            final GeoCosmetic geo = Assets.get("geo", GeoCosmetic.essentialKey(entry.id, snapshot.variantOf(entry.id)),
                    GeoCosmetic::load);
            if (geo == null || geo.torsoFront == null || hiddenByArmor(player, geo.hiddenByArmor)
                    || ((slim ? geo.skinCoverAlex : geo.skinCoverSteve) & com.ootruffle.clientmetics.model.SkinCover.JACKET) == 0) {
                continue;
            }
            // Hidden under another worn cosmetic (a maid outfit's body under a hoodie)?
            final java.util.Set<String> hidden = hiddenBones(snapshot, entry.id, geo);
            if (hidden != null && hidden.contains("body")) {
                continue;
            }
            if (best == null || outer(geo) > outer(best)) {
                best = geo;
            }
        }
        return best;
    }

    /** The Essential id of the worn cosmetic whose model is {@code geo} (from {@link #torsoCover}), or null. */
    public static String essentialIdOf(AbstractClientPlayer player, GeoCosmetic geo) {
        final EssentialSnapshot snapshot = geo == null ? null : PreviewRenderer.essentialOutfitFor(player.getUniqueID());
        final EssentialCatalog catalog = snapshot == null ? null : EssentialCatalog.get();
        if (catalog == null) {
            return null;
        }
        for (String cosmeticId : snapshot.equipped.values()) {
            final EssentialCatalog.Entry entry = catalog.entry(cosmeticId);
            if (entry != null && Assets.get("geo", GeoCosmetic.essentialKey(entry.id, snapshot.variantOf(entry.id)),
                    GeoCosmetic::load) == geo) {
                return entry.id;
            }
        }
        return null;
    }

    private static float outer(GeoCosmetic geo) {
        return geo.torsoFront[geo.torsoFront.length - 1][2];
    }

    /** Starts loading what an Essential outfit needs. Any thread; the downloads happen on the asset pool. */
    public static void preloadEssential(EssentialSnapshot snapshot) {
        final EssentialCatalog catalog = EssentialCatalog.get();
        if (catalog == null || snapshot == null) {
            return;
        }
        for (String cosmeticId : snapshot.equipped.values()) {
            final EssentialCatalog.Entry entry = catalog.entry(cosmeticId);
            if (!EssentialCatalog.isWearable(entry)) {
                continue; // Emotes, BASE_* defaults and unresolved cape hashes: nothing to fetch.
            }
            final String id = entry.id;
            final String variant = snapshot.variantOf(id);
            if (!essentialTypeShown(entry.type, Config.get())) {
                continue;
            }
            // Fetching a cosmetic's files is a network call, so keep it off the socket thread.
            Assets.EXECUTOR.execute(() -> {
                final GeoCosmetic geo = Assets.get("geo", GeoCosmetic.essentialKey(id, variant), GeoCosmetic::load);
                if (geo != null && !geo.isEmptyModel()) {
                    Textures.preload(geo.texturePath, "CAPE".equals(geo.type) ? Textures.Kind.CLOAK : Textures.Kind.MODEL);
                }
            });
        }
    }

    @Override
    public void doRenderLayer(AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTicks,
                              float ageInTicks, float netHeadYaw, float headPitch, float scale) {
        final Config config = Config.get();
        final Catalog catalog = Catalog.get();
        if (!config.enabled || player.isInvisible()) {
            return;
        }
        final List<EquippedCosmetic> outfit = catalog == null || !config.lunarOn() ? java.util.Collections.<EquippedCosmetic>emptyList()
                : PreviewRenderer.outfitFor(player.getUniqueID());
        // Wardrobe previews show one outfit (Lunar or Dawn) on its own.
        final DawnCatalog dawnCatalog = config.dawnOn() ? DawnCatalog.get() : null;
        final Collection<String> dawn = dawnCatalog == null ? null : PreviewRenderer.dawnOutfitFor(player.getUniqueID());
        final EssentialCatalog essentialCatalog = config.essentialOn() ? EssentialCatalog.get() : null;
        EssentialSnapshot essential = essentialCatalog == null ? null
                : PreviewRenderer.essentialOutfitFor(player.getUniqueID());
        if (player == Minecraft.getMinecraft().thePlayer && !config.showOwn && !PreviewRenderer.isRendering()) {
            essential = null;
        }
        // This mod's own cosmetic, whatever else they wear.
        BellCollar.render(player, renderer.getMainModel(), partialTicks);
        if (outfit.isEmpty() && (dawn == null || dawn.isEmpty()) && (essential == null || essential.equipped.isEmpty())) {
            return;
        }
        final ModelPlayer model = renderer.getMainModel();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1, 1, 1, 1);
        Molang.Scope scope = null;
        for (EquippedCosmetic cosmetic : outfit) {
            final Catalog.Entry entry = catalog.entry(cosmetic.id);
            if (entry == null || "cloak".equals(entry.category)) {
                continue; // Unknown IDs (newer than this install's catalogue) are skipped.
            }
            if (entry.geckolib) {
                if (config.showGeckolib) {
                    if (scope == null) {
                        scope = scope(player, limbSwing, limbSwingAmount, partialTicks, netHeadYaw, headPitch);
                    }
                    renderGeo(player, model, geo(entry), cosmetic, scope, null, null, "lunar:" + cosmetic.id, null);
                }
            } else {
                renderObj(player, model, catalog, entry, cosmetic);
            }
        }
        if (dawn != null) {
            for (String slug : dawn) {
                final DawnCatalog.Entry entry = dawnCatalog.entry(slug);
                if (entry == null || entry.geo == null || !entry.hasReadableTexture() || !dawnTypeShown(entry.type, config)) {
                    continue;
                }
                if (scope == null) {
                    scope = scope(player, limbSwing, limbSwingAmount, partialTicks, netHeadYaw, headPitch);
                }
                renderGeo(player, model, Assets.get("geo", GeoCosmetic.dawnKey(slug), GeoCosmetic::load), null, scope, null, null,
                        "dawn:" + slug, null);
            }
        }
        if (essential != null) {
            for (String cosmeticId : essential.equipped.values()) {
                final EssentialCatalog.Entry entry = essentialCatalog.entry(cosmeticId);
                // Minecraft capes and "Cape Disabled" aren't models; Essential capes go through the
                // vanilla cape layer (see Cloaks).
                if (!EssentialCatalog.isWearable(entry) || "CAPE".equals(entry.type)
                        || !essentialTypeShown(entry.type, config)) {
                    continue;
                }
                final String variant = essential.variantOf(entry.id);
                final GeoCosmetic geo = Assets.get("geo", GeoCosmetic.essentialKey(entry.id, variant), GeoCosmetic::load);
                if (geo == null) {
                    continue;
                }
                if (scope == null) {
                    scope = scope(player, limbSwing, limbSwingAmount, partialTicks, netHeadYaw, headPitch);
                }
                // Many Essential clothes have a cube exactly the size of the body, on the skin's own
                // surface; pull cosmetics slightly towards the camera so they win instead of flickering.
                GlStateManager.enablePolygonOffset();
                GlStateManager.doPolygonOffset(-1.0F, -10.0F);
                renderGeo(player, model, geo, null, scope, essential.positionAdjustment(entry.id),
                        hiddenBones(essential, entry.id, geo), "essential:" + entry.id, null);
                GlStateManager.doPolygonOffset(0.0F, 0.0F);
                GlStateManager.disablePolygonOffset();
            }
        }
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.disableBlend();
    }

    /** Arm bones drawn in first person so far (dev autotest). */
    public static volatile int firstPersonBones;

    /**
     * Draws the Essential cosmetics' parts that follow an arm (a shirt's sleeve, gloves, bracelets)
     * on the first-person arm, after {@link RenderPlayer#renderRightArm} or {@code renderLeftArm}
     * has drawn it. Those leave the model posed for first person and the matrix in the player
     * model's space, as in the world, so the arm bones line up the same way. Render thread.
     */
    public static void renderFirstPersonArm(RenderPlayer renderer, AbstractClientPlayer player, boolean right,
                                            float partialTicks) {
        final Config config = Config.get();
        if (!config.enabled || !config.essentialOn() || player.isInvisible()
                || player == Minecraft.getMinecraft().thePlayer && !config.showOwn) {
            return;
        }
        final EssentialCatalog catalog = EssentialCatalog.get();
        final EssentialSnapshot essential = catalog == null ? null : PreviewRenderer.essentialOutfitFor(player.getUniqueID());
        if (essential == null || essential.equipped.isEmpty()) {
            return;
        }
        final ModelPlayer model = renderer.getMainModel();
        final Part arm = right ? Part.RIGHT_ARM : Part.LEFT_ARM;
        Molang.Scope scope = null;
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1, 1, 1, 1);
        for (String cosmeticId : essential.equipped.values()) {
            final EssentialCatalog.Entry entry = catalog.entry(cosmeticId);
            if (!EssentialCatalog.isWearable(entry) || "CAPE".equals(entry.type) || !essentialTypeShown(entry.type, config)) {
                continue;
            }
            final GeoCosmetic geo = Assets.get("geo", GeoCosmetic.essentialKey(entry.id, essential.variantOf(entry.id)),
                    GeoCosmetic::load);
            if (geo == null) {
                continue;
            }
            if (scope == null) {
                scope = scope(player, 0, 0, partialTicks, 0, 0);
            }
            // As in the world: the sleeve sits on the arm's own surface, so pull it towards the camera.
            GlStateManager.enablePolygonOffset();
            GlStateManager.doPolygonOffset(-1.0F, -10.0F);
            renderGeo(player, model, geo, null, scope, essential.positionAdjustment(entry.id),
                    hiddenBones(essential, entry.id, geo), "essential:" + entry.id, arm);
            GlStateManager.doPolygonOffset(0.0F, 0.0F);
            GlStateManager.disablePolygonOffset();
        }
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.disableBlend();
    }

    @Override
    public boolean shouldCombineTextures() {
        return false;
    }

    // ---- OBJ ----

    private static ObjModel obj(Catalog.ModelSettings settings) {
        return Assets.get("obj", settings.objPath(), path -> ObjModel.parse(AssetFiles.read(path)));
    }

    private static void renderObj(AbstractClientPlayer player, ModelPlayer model, Catalog catalog, Catalog.Entry entry,
                                  EquippedCosmetic cosmetic) {
        final Catalog.ModelSettings settings = catalog.model(entry.modelName);
        if (settings == null) {
            return; // Old non-GeckoLib wings and similar have no shared model to draw.
        }
        final boolean head = "HEAD".equalsIgnoreCase(settings.bodyPart);
        final Config config = Config.get();
        if (head ? !config.showHats : !config.showBodywear) {
            return;
        }
        final boolean helmet = player.getCurrentArmor(3) != null;
        if (!PreviewRenderer.isPreviewing()
                && (head ? helmet && !cosmetic.showOverHelmet : player.getCurrentArmor(2) != null && !cosmetic.showOverChestplate)) {
            return;
        }
        final ObjModel obj = obj(settings);
        final ResourceLocation texture = Textures.get(entry.resource, Textures.Kind.MODEL,
                Recolours.colour(player.getUniqueID(), "lunar:" + cosmetic.id));
        if (obj == null || texture == null) {
            return;
        }
        Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
        GlStateManager.pushMatrix();
        if (player.isSneaking()) {
            GlStateManager.translate(0, SNEAK_DROP, 0);
        }
        (head ? model.bipedHead : model.bipedBody).postRender(0.0625F);
        if (head && cosmetic.hatHeightOffset != 0) {
            float offset = cosmetic.hatHeightOffset;
            if (settings.minHeightOffset < settings.maxHeightOffset) {
                offset = MathHelper.clamp_float(offset, settings.minHeightOffset, settings.maxHeightOffset);
            }
            GlStateManager.translate(0, -offset * HEIGHT_OFFSET_UNIT, 0); // Model space is Y down.
        }
        if (head) {
            // Lunar's head attachment is turned a quarter turn from the vanilla head bone: its
            // head models face +Z and their transforms begin with a 90 degree turn, which only
            // lands masks, glasses and bandanna knots right with this base turn.
            GlStateManager.rotate(90, 0, 1, 0);
        }
        applyTransforms(settings.transformations, helmet, player.isWearing(EnumPlayerModelParts.HAT));
        obj.render();
        GlStateManager.popMatrix();
    }

    /**
     * Applies {@code transformations} in order. Each one's values are either plain (always used),
     * or keyed: {@code player} (in-world), {@code helmet} / {@code skin_layer} (scale variants while
     * a helmet or the hat layer is on) and {@code gui} (menus only, ignored here).
     */
    private static void applyTransforms(Iterable<JsonElement> transforms, boolean helmet, boolean hatLayer) {
        for (JsonElement t : transforms) {
            if (!t.isJsonObject()) {
                continue;
            }
            final JsonObject o = t.getAsJsonObject();
            final String type = o.has("transformType") ? o.get("transformType").getAsString() : "";
            final JsonObject values = o.has("values") && o.get("values").isJsonObject() ? o.getAsJsonObject("values") : null;
            if (values == null) {
                continue;
            }
            JsonObject v = null;
            if (values.has("player") && values.get("player").isJsonObject()) {
                v = values.getAsJsonObject("player");
            } else if (helmet && values.has("helmet") && values.get("helmet").isJsonObject()) {
                v = values.getAsJsonObject("helmet");
            } else if (!helmet && hatLayer && values.has("skin_layer") && values.get("skin_layer").isJsonObject()) {
                v = values.getAsJsonObject("skin_layer");
            } else if (values.has("x") || values.has("angle")) {
                v = values;
            }
            if (v == null) {
                continue;
            }
            final float x = num(v, "x", "scale".equals(type) ? 1 : 0);
            final float y = num(v, "y", "scale".equals(type) ? 1 : 0);
            final float z = num(v, "z", "scale".equals(type) ? 1 : 0);
            switch (type) {
                case "translate":
                    GlStateManager.translate(x, y, z);
                    break;
                case "scale":
                    GlStateManager.scale(x, y, z);
                    break;
                case "rotate":
                    GlStateManager.rotate(num(v, "angle", 0), x, y, z);
                    break;
                default:
                    break;
            }
        }
    }

    private static float num(JsonObject o, String key, float def) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsFloat() : def;
    }

    // ---- GeckoLib ----

    private static GeoCosmetic geo(Catalog.Entry entry) {
        return Assets.get("geo", entry.resource, GeoCosmetic::load);
    }

    /** Body groups in Essential's {@code COSMETIC_BONE_HIDING} rules, and the bones that start each. */
    private static final java.util.Map<String, String[]> BONE_GROUPS = new java.util.HashMap<>();
    static {
        BONE_GROUPS.put("head", new String[]{"head"});
        BONE_GROUPS.put("body", new String[]{"body"});
        BONE_GROUPS.put("arms", new String[]{"arm_right", "arm_left"});
        BONE_GROUPS.put("legs", new String[]{"leg_right", "leg_left"});
    }

    /**
     * Bones of one Essential cosmetic to leave out, given the rest of the outfit: what other worn
     * cosmetics hide of it ({@code EXTERNAL_HIDDEN_BONE}), and the parts of it that hide under
     * them ({@code COSMETIC_BONE_HIDING}). Without these, two clothes on the same surface (a
     * hoodie over a maid outfit) are both drawn and fight. Null when nothing is hidden.
     */
    public static java.util.Set<String> hiddenBones(EssentialSnapshot outfit, String id, GeoCosmetic geo) {
        java.util.Set<String> out = null;
        for (String other : outfit.equipped.values()) {
            if (other.equals(id)) {
                continue;
            }
            final java.util.Set<String> groups = geo.hiddenUnder.get(other);
            if (groups != null) {
                for (String group : groups) {
                    final String[] bones = BONE_GROUPS.get(group);
                    if (bones != null) {
                        if (out == null) {
                            out = new java.util.HashSet<>();
                        }
                        out.addAll(java.util.Arrays.asList(bones));
                    }
                }
            }
            final GeoCosmetic otherGeo = Assets.get("geo", GeoCosmetic.essentialKey(other, outfit.variantOf(other)),
                    GeoCosmetic::load);
            final java.util.Set<String> external = otherGeo == null ? null : otherGeo.hidesBonesOf.get(id);
            if (external != null) {
                if (out == null) {
                    out = new java.util.HashSet<>();
                }
                out.addAll(external);
            }
        }
        return out;
    }

    /**
     * {@code cosmetic} is the Lunar equip settings, or null for a Dawn or Essential cosmetic (its own
     * data decides). {@code adjustment} is an extra block offset, or null; {@code hiddenBones} are
     * bone names to leave out, or null. {@code key} names the cosmetic for {@link Recolours}.
     */
    private static void renderGeo(AbstractClientPlayer player, ModelPlayer model, GeoCosmetic geo,
                                  EquippedCosmetic cosmetic, Molang.Scope scope, float[] adjustment,
                                  java.util.Set<String> hiddenBones, String key, Part only) {
        if (geo == null || geo.isEmptyModel()) {
            return; // Particle-only effects: nothing to draw, so don't upload their particle sheets.
        }
        if (cosmetic != null ? hiddenByArmor(player, geo.type, cosmetic) : hiddenByArmor(player, geo.hiddenByArmor)) {
            return;
        }
        final ResourceLocation texture = Textures.get(geo.texturePath, Textures.Kind.MODEL,
                Recolours.colour(player.getUniqueID(), key));
        if (texture == null) {
            return;
        }
        geo.applyOptions(scope);
        final GeoAnimation animation = Config.get().animate ? geo.currentAnimation(scope) : null;
        final double animTime = scope.get("query.anim_time");
        final Part rootPart = "HEAD".equalsIgnoreCase(geo.attachedBone) ? Part.HEAD
                : "SHOULDER".equalsIgnoreCase(geo.attachedBone) ? Part.BODY : null;
        if (only != null && (geo.anchored || rootPart != null)) {
            return; // Drawn whole on the head or body: nothing of it follows an arm.
        }

        Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
        GlStateManager.pushMatrix();
        // Undo the player renderer's flip: feet at the origin, Y up, X un-mirrored.
        GlStateManager.translate(0, MODEL_ORIGIN, 0);
        GlStateManager.scale(-1, -1, 1);
        if (player.isSneaking() && only == null) { // The first-person arm never sneaks.
            GlStateManager.translate(0, -SNEAK_DROP, 0);
        }
        if (adjustment != null) {
            GlStateManager.translate(adjustment[0], adjustment[1], adjustment[2]);
        }
        GlStateManager.scale(1 / 16F, 1 / 16F, 1 / 16F);
        for (float[] offset : geo.armorOffsets) {
            if (player.getCurrentArmor((int) offset[0]) != null) {
                GlStateManager.translate(offset[1], offset[2], offset[3]);
            }
        }
        geo.model(scope).render(new GeoModel.BoneHook() {
            @Override
            public void beforeBone(GeoModel.Bone bone) {
                if (geo.anchored) {
                    final Part anchor = anchorFor(bone.name);
                    if (anchor != null) {
                        followPart(part(model, anchor));
                    }
                    return;
                }
                if (rootPart != null) {
                    if (bone.isRoot()) {
                        followPart(part(model, rootPart));
                    }
                    return;
                }
                final Part part = partFor(bone.name);
                if (part != null) {
                    followPart(part(model, part));
                }
            }

            @Override
            public boolean skip(GeoModel.Bone bone) {
                return hiddenBones != null && hiddenBones.contains(bone.name.toLowerCase(Locale.ROOT));
            }

            @Override
            public boolean draws(GeoModel.Bone bone) {
                if (only == null) {
                    return true;
                }
                if (partFor(bone.name) == only) {
                    firstPersonBones++;
                    return true;
                }
                return false;
            }

            @Override
            public GeoAnimation.Pose pose(GeoModel.Bone bone) {
                if (animation == null) {
                    return null;
                }
                final GeoAnimation.Pose pose = new GeoAnimation.Pose();
                return animation.pose(bone.name, animTime, scope, pose) ? pose : null;
            }
        });
        GlStateManager.popMatrix();
    }

    private static boolean hiddenByArmor(AbstractClientPlayer player, String type, EquippedCosmetic cosmetic) {
        if (PreviewRenderer.isPreviewing()) {
            return false; // Wardrobe previews always show the cosmetic.
        }
        switch (type.toLowerCase(Locale.ROOT)) {
            case "hat":
            case "glasses":
            case "mask":
                return !Config.get().showHats || player.getCurrentArmor(3) != null && !cosmetic.showOverHelmet;
            case "backpack":
            case "suits":
            case "bodywear":
            case "neckwear":
                return player.getCurrentArmor(2) != null && !cosmetic.showOverChestplate;
            case "belts":
                return player.getCurrentArmor(1) != null && !cosmetic.showOverLeggings;
            case "shoes":
                return player.getCurrentArmor(0) != null && !cosmetic.showOverBoots;
            default:
                return false;
        }
    }

    private static boolean hiddenByArmor(AbstractClientPlayer player, int[] slots) {
        if (PreviewRenderer.isPreviewing()) {
            return false; // Wardrobe previews always show the cosmetic.
        }
        for (int slot : slots) {
            if (player.getCurrentArmor(slot) != null) {
                return true;
            }
        }
        return false;
    }

    /** Dawn's attachment bones: {@code anchor_head}, {@code anchor_body}, {@code anchor_leftarm}... */
    private static Part anchorFor(String boneName) {
        final String n = boneName.toLowerCase(Locale.ROOT);
        return n.startsWith("anchor_") ? partFor(n.substring(7)) : null;
    }

    /**
     * Applies a vanilla limb's rotation about its pivot, converted from the player model's space
     * (Y and X flipped) into GeckoLib's: X and Y rotations change sign, Z doesn't. Units are pixels.
     */
    private static void followPart(ModelRenderer limb) {
        final float px = -limb.rotationPointX;
        final float py = MODEL_ORIGIN * 16 - limb.rotationPointY;
        final float pz = limb.rotationPointZ;
        GlStateManager.translate(px, py, pz);
        if (limb.rotateAngleZ != 0) {
            GlStateManager.rotate((float) Math.toDegrees(limb.rotateAngleZ), 0, 0, 1);
        }
        if (limb.rotateAngleY != 0) {
            GlStateManager.rotate((float) -Math.toDegrees(limb.rotateAngleY), 0, 1, 0);
        }
        if (limb.rotateAngleX != 0) {
            GlStateManager.rotate((float) -Math.toDegrees(limb.rotateAngleX), 1, 0, 0);
        }
        GlStateManager.translate(-px, -py, -pz);
    }

    private static ModelRenderer part(ModelPlayer model, Part part) {
        switch (part) {
            case HEAD:
                return model.bipedHead;
            case BODY:
                return model.bipedBody;
            case RIGHT_ARM:
                return model.bipedRightArm;
            case LEFT_ARM:
                return model.bipedLeftArm;
            case RIGHT_LEG:
                return model.bipedRightLeg;
            default:
                return model.bipedLeftLeg;
        }
    }

    /** {@code bipedHead}, {@code armorHead}, {@code Head}, {@code right_arm}, {@code RightArm}... */
    private static Part partFor(String boneName) {
        String n = boneName.toLowerCase(Locale.ROOT).replace("_", "");
        if (n.startsWith("biped")) {
            n = n.substring(5);
        } else if (n.startsWith("armor")) {
            n = n.substring(5);
        }
        switch (n) {
            case "head":
                return Part.HEAD;
            case "body":
                return Part.BODY;
            case "rightarm":
            case "armright": // Essential: arm_right
                return Part.RIGHT_ARM;
            case "leftarm":
            case "armleft":
                return Part.LEFT_ARM;
            case "rightleg":
            case "legright":
                return Part.RIGHT_LEG;
            case "leftleg":
            case "legleft":
                return Part.LEFT_LEG;
            default:
                return null;
        }
    }

    /**
     * The Molang queries cosmetic animations read. Speeds are blocks per second; local velocities
     * are relative to the body (Z forward, X sideways). Anything not set here reads as 0.
     */
    private static Molang.Scope scope(AbstractClientPlayer p, float limbSwing, float limbSwingAmount, float partialTicks,
                                      float netHeadYaw, float headPitch) {
        final double time = (p.ticksExisted + partialTicks) / 20.0;
        final double dx = (p.posX - p.prevPosX) * 20;
        final double dy = (p.posY - p.prevPosY) * 20;
        final double dz = (p.posZ - p.prevPosZ) * 20;
        final float bodyYaw = p.prevRenderYawOffset + (p.renderYawOffset - p.prevRenderYawOffset) * partialTicks;
        final double yaw = Math.toRadians(bodyYaw);
        final double localX = dx * Math.cos(yaw) + dz * Math.sin(yaw);
        final double localZ = -dx * Math.sin(yaw) + dz * Math.cos(yaw);
        final double speed = Math.sqrt(dx * dx + dz * dz);
        final double moving = Math.min(1, limbSwingAmount * 2);
        final boolean slim = "slim".equals(p.getSkinType());
        return new Molang.Scope()
                .set("query.anim_time", time)
                .set("query.life_time", time)
                .set("query.head_y_rotation", netHeadYaw)
                .set("query.head_x_rotation", headPitch)
                .set("query.body_y_rotation", bodyYaw)
                .set("query.is_crouching", p.isSneaking() ? 1 : 0)
                .set("query.is_sneaking", p.isSneaking() ? 1 : 0)
                .set("query.is_running", p.isSprinting() ? 1 : 0)
                .set("query.is_sprinting", p.isSprinting() ? 1 : 0)
                .set("query.is_swimming", p.isInWater() ? 1 : 0)
                .set("query.is_in_water", p.isInWater() ? 1 : 0)
                .set("query.is_on_ground", p.onGround ? 1 : 0)
                .set("query.is_moving", speed > 0.1 ? 1 : 0)
                .set("query.ground_speed", speed)
                .set("query.ground_speed_smoothed", speed)
                .set("context.ground_speed_smoothed", speed)
                .set("query.modified_move_speed", limbSwingAmount)
                .set("query.modified_distance_moved", limbSwing)
                .set("query.y_velocity", dy)
                .set("query.local_x_velocity", localX)
                .set("query.local_z_velocity", localZ)
                .set("context.local_x_velocity_smoothed", localX)
                .set("context.local_y_velocity_smoothed", dy)
                .set("context.local_z_velocity_smoothed", localZ)
                .set("context.y_velocity_smoothed", dy)
                .set("context.lvs_x", localX)
                .set("context.lvs_y", dy)
                .set("context.lvs_z", localZ)
                .set("context.is_moving_smoothed", moving)
                .set("context.is_walking_smoothed", moving)
                .set("context.movement_speed_smoothed", speed)
                .set("context.normalized_ground_speed", Math.min(1, speed / 5.6))
                .set("context.distance_moved", p.distanceWalkedModified)
                .set("context.timer", time)
                .set("variable.is_slim_skin", slim ? 1 : 0)
                .set("query.is_slim_model", slim ? 1 : 0)
                .set("variable.moving", speed > 0.1 ? 1 : 0)
                .set("variable.idle", speed > 0.1 ? 0 : 1);
    }

}
