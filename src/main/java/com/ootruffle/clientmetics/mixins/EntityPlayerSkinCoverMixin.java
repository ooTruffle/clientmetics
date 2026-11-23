package com.ootruffle.clientmetics.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.ootruffle.clientmetics.model.SkinCover;
import com.ootruffle.clientmetics.render.CosmeticRenderer;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Reports an outer skin layer as not worn while an Essential cosmetic covers it (see
 * {@link SkinCover}), as Essential's skin masks intend. Vanilla's player renderer reads this to
 * show the layer, and so do mods that draw it differently: 3D Skin Layers attaches its voxel
 * layers to the same model parts, and the Feminine Gender Mod draws its jacket overlay only when
 * the jacket is worn. Without it those layers poke through clothing and z-fight with it.
 */
@Mixin(EntityPlayer.class)
public abstract class EntityPlayerSkinCoverMixin {

    @ModifyReturnValue(method = "isWearing", at = @At("RETURN"))
    private boolean clientmetics$coveredByCosmetic(boolean original, EnumPlayerModelParts part) {
        if (!original || !((Object) this instanceof AbstractClientPlayer)) {
            return original;
        }
        return !CosmeticRenderer.coversSkinLayer((AbstractClientPlayer) (Object) this, SkinCover.bit(part));
    }

}
