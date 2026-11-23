package com.ootruffle.clientmetics.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.ootruffle.clientmetics.render.Cloaks;
import com.ootruffle.clientmetics.render.PreviewRenderer;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Swaps in the player's equipped cloak as their cape texture.
 * <p>
 * It changes the return value (rather than cancelling the method), at a lower priority than
 * PolyPlus's own hook (1500), so PolyPlus still sees the result. Cloak textures are in the
 * {@code clientmetics} namespace, which PolyPlus recognises as another mod's cape: it keeps it,
 * or replaces it with the PolyPlus cape when "Show over Clientmetics" is on.
 * <p>
 * In a wardrobe preview the player's own cape is noise, so no cape is reported at all when no cloak
 * cosmetic applies. Doing it here rather than in the cape layer covers every renderer: vanilla,
 * PolyPlus and cape mods (Wavey Capes and friends) all ask for this texture first.
 */
@Mixin(value = AbstractClientPlayer.class, priority = 900)
public abstract class AbstractClientPlayerMixin {

    @ModifyReturnValue(method = "getLocationCape", at = @At("RETURN"))
    private ResourceLocation clientmetics$cloak(ResourceLocation original) {
        final java.util.UUID uuid = ((AbstractClientPlayer) (Object) this).getUniqueID();
        final ResourceLocation cloak = Cloaks.capeFor(uuid);
        if (cloak != null) {
            return cloak;
        }
        // Essential's "Cape Disabled", or an Essential cape that's still loading.
        if (Cloaks.hidesVanillaCape(uuid)) {
            return null;
        }
        return PreviewRenderer.isPreviewing() ? null : original;
    }

}
