package com.ootruffle.clientmetics.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.ootruffle.clientmetics.render.Cloaks;
import com.ootruffle.clientmetics.render.PreviewRenderer;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Wardrobe previews always show the Lunar cloak being previewed. This runs after PolyPlus's cape
 * hook (higher priority than its 1500), but only while a preview is drawn, so in the world
 * PolyPlus's "Show over other mods" still decides (see {@link AbstractClientPlayerMixin}).
 */
@Mixin(value = AbstractClientPlayer.class, priority = 2000)
public abstract class AbstractClientPlayerPreviewMixin {

    @ModifyReturnValue(method = "getLocationCape", at = @At("RETURN"))
    private ResourceLocation clientmetics$previewCloak(ResourceLocation original) {
        if (!PreviewRenderer.isPreviewing()) {
            return original;
        }
        final ResourceLocation cloak = Cloaks.capeFor(((AbstractClientPlayer) (Object) this).getUniqueID());
        return cloak != null ? cloak : original;
    }

}
