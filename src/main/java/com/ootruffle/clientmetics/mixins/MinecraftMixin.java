package com.ootruffle.clientmetics.mixins;

import com.ootruffle.clientmetics.Clientmetics;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "runTick", at = @At("TAIL"))
    private void clientmetics$onTick(CallbackInfo ci) {
        Clientmetics.onClientTick();
    }

}
