package com.ootruffle.clientmetics.mixins;

import com.ootruffle.clientmetics.render.CosmeticRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the cosmetics layer to both player renderers (wide and slim arms). */
@Mixin(RenderPlayer.class)
public abstract class RenderPlayerMixin {

    @Inject(method = "<init>(Lnet/minecraft/client/renderer/entity/RenderManager;Z)V", at = @At("TAIL"))
    private void clientmetics$addLayer(RenderManager manager, boolean slim, CallbackInfo ci) {
        ((RendererLivingEntityAccessor) this).clientmetics$addLayer(new CosmeticRenderer((RenderPlayer) (Object) this));
    }

}
