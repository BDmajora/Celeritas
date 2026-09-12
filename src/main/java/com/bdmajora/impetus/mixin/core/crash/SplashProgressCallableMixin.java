package com.bdmajora.impetus.mixin.core.crash;

import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Patches the SplashProgress inner Callable; skips its GL calls when no display context exists, avoiding a crash on early loading screens
@Mixin(targets = {"net/minecraftforge/fml/client/SplashProgress$1"})
public class SplashProgressCallableMixin {
    // Reports GL info only when a context is current, so the crash report does not itself crash
    @Inject(method = "call()Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private void checkContext(CallbackInfoReturnable<String> cir) {
        boolean isContextAvailable;
        try {
            isContextAvailable = Display.isCreated() && Display.getDrawable().isCurrent();
        } catch (Exception e) {
            isContextAvailable = false;
        }
        if (!isContextAvailable) {
            cir.setReturnValue("No context available");
        }
    }
}
