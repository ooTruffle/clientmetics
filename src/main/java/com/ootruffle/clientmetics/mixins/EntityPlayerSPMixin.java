package com.ootruffle.clientmetics.mixins;

import com.ootruffle.clientmetics.Clientmetics;
import java.util.Locale;
import net.minecraft.client.entity.EntityPlayerSP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code /lunarwardrobe}, {@code /dawnwardrobe} and {@code /essentialwardrobe}: handled on the client, never sent to the server. Works without OneConfig. */
@Mixin(EntityPlayerSP.class)
public abstract class EntityPlayerSPMixin {

    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void clientmetics$wardrobeCommand(String message, CallbackInfo ci) {
        final String m = message.trim().toLowerCase(Locale.ROOT);
        if (m.equals("/lunarwardrobe") || m.equals("/clientmetics wardrobe")) {
            Clientmetics.openWardrobe();
            ci.cancel();
        } else if (m.equals("/dawnwardrobe") || m.equals("/clientmetics wardrobe dawn")) {
            Clientmetics.openDawnWardrobe();
            ci.cancel();
        } else if (m.equals("/essentialwardrobe") || m.equals("/clientmetics wardrobe essential")) {
            Clientmetics.openEssentialWardrobe();
            ci.cancel();
        } else if (m.equals("/clientmetics essential") || m.equals("/essentialdebug")) {
            // Prints what Essential support actually has, so a log answers "why is this empty?".
            com.ootruffle.clientmetics.cosmetic.EssentialDiagnostics.report();
            ci.cancel();
        }
    }

}
