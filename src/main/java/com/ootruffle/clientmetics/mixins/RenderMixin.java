package com.ootruffle.clientmetics.mixins;

import com.ootruffle.clientmetics.render.PreviewRenderer;
import net.minecraft.client.renderer.entity.Render;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No nametags (or the score line under them) on the wardrobe's previews. */
@Mixin(Render.class)
public abstract class RenderMixin {

    @Inject(method = "renderLivingLabel", at = @At("HEAD"), cancellable = true)
    private void clientmetics$noLabelInPreview(CallbackInfo ci) {
        if (PreviewRenderer.isRendering()) {
            ci.cancel();
        }
    }

}
