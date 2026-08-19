package com.bdmajora.impetus.mixin.core.shader;

import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bdmajora.impetus.iris.uniforms.CapturedRenderingState;

@Mixin(GlStateManager.class)
public class GlStateManagerAlphaMixin {
    @Inject(method = "alphaFunc(IF)V", at = @At("HEAD"))
    private static void impetus$captureAlphaFunc(int func, float ref, CallbackInfo ci) {
        CapturedRenderingState.INSTANCE.setCurrentAlphaTest(ref);
    }
}
