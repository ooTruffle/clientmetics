package com.ootruffle.clientmetics.mixins;

import com.feminine.gender.client.render.BreastSide;
import com.ootruffle.clientmetics.compat.FeminineGenderCompat;
import net.minecraft.client.entity.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feminine Gender Mod (optional; nothing happens without it): draws an Essential top over its
 * breasts, so the top follows them instead of the breasts poking through (see
 * {@link FeminineGenderCompat}).
 *
 * <p>{@code render} and {@code renderBreast} are the mod's own names, the same in production and
 * in dev runs, so nothing is remapped. (Its bridge method from the layer interface is left alone.)
 */
@Pseudo
@Mixin(targets = "com.feminine.gender.client.render.GenderLayer", remap = false)
public abstract class FeminineGenderLayerMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void clientmetics$beginLayer(AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                                         float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
                                         float scale, CallbackInfo ci) {
        if (FeminineGenderCompat.beginLayer(player)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderBreast", at = @At("HEAD"), require = 0, remap = false)
    private void clientmetics$coverBreast(BreastSide side, float scale, CallbackInfo ci) {
        FeminineGenderCompat.coverBreast(side.isLeft(), scale);
    }

}
