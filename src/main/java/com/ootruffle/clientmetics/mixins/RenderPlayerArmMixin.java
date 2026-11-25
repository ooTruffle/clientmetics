package com.ootruffle.clientmetics.mixins;

import com.ootruffle.clientmetics.render.CosmeticRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws Essential sleeves, gloves and the like on the first-person arm, once vanilla has drawn the
 * arm (see {@link CosmeticRenderer#renderFirstPersonArm}). The left arm is drawn when holding a map.
 */
@Mixin(RenderPlayer.class)
public abstract class RenderPlayerArmMixin {

    @Inject(method = "renderRightArm", at = @At("TAIL"))
    private void clientmetics$rightArmCosmetics(AbstractClientPlayer player, CallbackInfo ci) {
        CosmeticRenderer.renderFirstPersonArm((RenderPlayer) (Object) this, player, true, partialTicks());
    }

    @Inject(method = "renderLeftArm", at = @At("TAIL"))
    private void clientmetics$leftArmCosmetics(AbstractClientPlayer player, CallbackInfo ci) {
        CosmeticRenderer.renderFirstPersonArm((RenderPlayer) (Object) this, player, false, partialTicks());
    }

    private static float partialTicks() {
        return ((MinecraftTimerAccessor) Minecraft.getMinecraft()).clientmetics$timer().renderPartialTicks;
    }

}
