package com.ootruffle.clientmetics.mixins;

import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RendererLivingEntity.class)
public interface RendererLivingEntityAccessor {

    @Invoker("addLayer")
    boolean clientmetics$addLayer(LayerRenderer<?> layer);

}
