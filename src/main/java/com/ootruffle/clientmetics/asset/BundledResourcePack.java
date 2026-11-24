package com.ootruffle.clientmetics.asset;

import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Set;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.client.resources.data.IMetadataSerializer;
import net.minecraft.util.ResourceLocation;

/**
 * Puts the mod's own {@code assets/clientmetics} into the game's resources, so the game can load
 * what it has to load itself (the bell collar's sound, through {@code sounds.json}). Without this,
 * only a mod like OSL's resource loader would do it. Textures don't need it: they're read straight
 * from the jar (see {@link AssetFiles}).
 */
public final class BundledResourcePack implements IResourcePack {

    public static final BundledResourcePack INSTANCE = new BundledResourcePack();
    private static final String DOMAIN = "clientmetics";

    private BundledResourcePack() {}

    private static String path(ResourceLocation location) {
        return "/assets/" + location.getResourceDomain() + "/" + location.getResourcePath();
    }

    @Override
    public InputStream getInputStream(ResourceLocation location) throws IOException {
        final InputStream in = DOMAIN.equals(location.getResourceDomain())
                ? BundledResourcePack.class.getResourceAsStream(path(location)) : null;
        if (in == null) {
            throw new FileNotFoundException(location.toString());
        }
        return in;
    }

    @Override
    public boolean resourceExists(ResourceLocation location) {
        return DOMAIN.equals(location.getResourceDomain())
                && BundledResourcePack.class.getResource(path(location)) != null;
    }

    @Override
    public Set<String> getResourceDomains() {
        return Collections.singleton(DOMAIN);
    }

    @Override
    public <T extends IMetadataSection> T getPackMetadata(IMetadataSerializer serializer, String section) {
        return null;
    }

    @Override
    public BufferedImage getPackImage() throws IOException {
        throw new FileNotFoundException("pack.png");
    }

    @Override
    public String getPackName() {
        return "Clientmetics";
    }

}
