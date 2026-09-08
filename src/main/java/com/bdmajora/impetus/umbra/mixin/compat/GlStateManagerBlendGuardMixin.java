package com.bdmajora.impetus.umbra.mixin.compat;

import com.bdmajora.impetus.umbra.gl.blending.BlendOverrideGuard;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Intercepts vanilla/mod calls to GlStateManager.enableBlend so a shader pack's own blend state isn't stomped mid-frame
@Mixin(GlStateManager.class)
public abstract class GlStateManagerBlendGuardMixin {
    // Cancel the vanilla call outright if the guard says a pack-controlled blend override is active
    @Inject(method = "enableBlend()V", at = @At("HEAD"), cancellable = true)
    private static void impetus$deferEnableBlend(CallbackInfo ci) {
        if (BlendOverrideGuard.recordEnableBlend()) {
            ci.cancel();
        }
    }
}
