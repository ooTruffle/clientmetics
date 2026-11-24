package com.ootruffle.clientmetics.mixins;

import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.asset.BundledResourcePack;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResourcePack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Shadow
    @Final
    private List<IResourcePack> defaultResourcePacks;

    @Inject(method = "runTick", at = @At("TAIL"))
    private void clientmetics$onTick(CallbackInfo ci) {
        Clientmetics.onClientTick();
    }

    /** The game reads its resource packs from this list on every reload, the first time included. */
    @Inject(method = "refreshResources", at = @At("HEAD"))
    private void clientmetics$addBundledPack(CallbackInfo ci) {
        if (!defaultResourcePacks.contains(BundledResourcePack.INSTANCE)) {
            defaultResourcePacks.add(BundledResourcePack.INSTANCE);
        }
    }

}
