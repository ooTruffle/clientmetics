package com.ootruffle.clientmetics.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The frame's partial tick, for drawing that isn't handed one (the first-person arm). */
@Mixin(Minecraft.class)
public interface MinecraftTimerAccessor {

    @Accessor("timer")
    Timer clientmetics$timer();

}
