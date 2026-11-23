package com.ootruffle.clientmetics.render;

import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.asset.Assets;
import com.ootruffle.clientmetics.asset.Catalog;
import com.ootruffle.clientmetics.asset.DawnCatalog;
import com.ootruffle.clientmetics.asset.DawnFiles;
import com.ootruffle.clientmetics.asset.EssentialCatalog;
import com.ootruffle.clientmetics.asset.Textures;
import com.ootruffle.clientmetics.cosmetic.EquippedCosmetic;
import com.ootruffle.clientmetics.cosmetic.EssentialSnapshot;
import com.ootruffle.clientmetics.model.GeoCosmetic;
import java.util.UUID;
import net.minecraft.util.ResourceLocation;

/**
 * Lunar cloaks drawn through the vanilla cape layer: the player's cape texture is swapped for the
 * cloak's current animation frame, so the vanilla rules still apply (the player's own cape
 * setting, invisibility, and so on). Cloth cloaks are drawn as ordinary capes.
 * <p>
 * Dawn cloaks use the same 22x17 cape layout, so they go through the same path. Only the WebP
 * ones can be shown: most Dawn cloaks are animated AVIF, which Java can't decode.
 */
public final class Cloaks {

    private Cloaks() {}

    /** The cloak texture to draw for this player, or null for their normal cape. Render thread only. */
    public static ResourceLocation capeFor(UUID uuid) {
        final Config config = Config.get();
        final Catalog catalog = Catalog.get();
        if (!config.enabled || !config.showCloaks || uuid == null) {
            return null;
        }
        if (catalog != null && config.lunar) {
            for (EquippedCosmetic cosmetic : PreviewRenderer.outfitFor(uuid)) {
                final Catalog.Entry entry = catalog.entry(cosmetic.id);
                if (entry != null && "cloak".equals(entry.category)) {
                    return Textures.get(entry.resource, Textures.Kind.CLOAK);
                }
            }
        }
        final DawnCatalog dawnCatalog = config.dawn ? DawnCatalog.get() : null;
        final java.util.Collection<String> dawn = dawnCatalog == null ? null : PreviewRenderer.dawnOutfitFor(uuid);
        if (dawn != null) {
            for (String slug : dawn) {
                final DawnCatalog.Entry entry = dawnCatalog.entry(slug);
                if (entry != null && "cape".equals(entry.type) && entry.hasReadableTexture()) {
                    return Textures.get(DawnFiles.PREFIX + entry.texture, Textures.Kind.CLOAK);
                }
            }
        }
        final String essential = essentialCapeTexture(uuid);
        return essential == null ? null : Textures.get(essential, Textures.Kind.CLOAK);
    }

    /** The equipped Essential CAPE-slot cosmetic (which may be a Minecraft cape or "Cape Disabled"), or null. */
    private static EssentialCatalog.Entry essentialCape(EssentialSnapshot snapshot) {
        final EssentialCatalog catalog = EssentialCatalog.get();
        if (snapshot == null || catalog == null) {
            return null;
        }
        for (String cosmeticId : snapshot.equipped.values()) {
            final EssentialCatalog.Entry entry = catalog.entry(cosmeticId);
            if (entry != null && "CAPE".equals(entry.type)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * The texture path of this player's Essential cape, or null (none, or still loading). Every
     * Essential cape uses the vanilla cape's box and 64x32 layout (animated ones as a strip of such
     * frames), so it goes through the vanilla cape layer like a Lunar cloak: it swings like a cape
     * and cape mods (WaveyCapes) draw it too.
     */
    private static String essentialCapeTexture(UUID uuid) {
        if (!Config.get().essential) {
            return null;
        }
        final EssentialSnapshot snapshot = PreviewRenderer.essentialOutfitFor(uuid);
        final EssentialCatalog.Entry entry = essentialCape(snapshot);
        if (entry == null || !EssentialCatalog.isWearable(entry)) {
            return null;
        }
        // The geometry load registers the frame size and resolves the colour variant, off-thread.
        final GeoCosmetic geo = Assets.get("geo", GeoCosmetic.essentialKey(entry.id, snapshot.variantOf(entry.id)),
                GeoCosmetic::load);
        return geo == null ? null : geo.texturePath;
    }

    /**
     * Whether the vanilla cape must be hidden when {@link #capeFor} has nothing: Essential's "Cape
     * Disabled", or an Essential cape whose texture is still loading (so the player's Minecraft cape
     * doesn't flash up first). A Minecraft cape picked in Essential is left to the vanilla layer,
     * which already shows the player's real Minecraft cape.
     */
    public static boolean hidesVanillaCape(UUID uuid) {
        final Config config = Config.get();
        if (!config.enabled || !config.showCloaks || !config.essential || uuid == null) {
            return false;
        }
        final EssentialCatalog.Entry entry = essentialCape(PreviewRenderer.essentialOutfitFor(uuid));
        return entry != null && !entry.isMojangCape();
    }

}
