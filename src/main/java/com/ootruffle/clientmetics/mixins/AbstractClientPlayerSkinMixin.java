package com.ootruffle.clientmetics.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.ootruffle.clientmetics.render.MaskedSkins;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gives a player wearing Essential clothing their skin with the covered outer-layer pixels cleared
 * (see {@link MaskedSkins}), as Essential's skin masks intend. Vanilla's player renderer and
 * first-person arms draw this texture, and so do mods that draw the skin differently: 3D Skin
 * Layers builds its voxel layers from it, and the Feminine Gender Mod textures its breasts with it.
 * Without it those layers poke through clothing and z-fight with it.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerSkinMixin {

    @ModifyReturnValue(method = "getLocationSkin()Lnet/minecraft/util/ResourceLocation;", at = @At("RETURN"))
    private ResourceLocation clientmetics$maskedSkin(ResourceLocation original) {
        return MaskedSkins.skinFor((AbstractClientPlayer) (Object) this, original);
    }

}
