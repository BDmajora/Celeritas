package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlStateManager.class)
public class GlStateManagerColorMixin {
    @Inject(method = "color(FFFF)V", at = @At("HEAD"))
    private static void iris$captureColor4(float red, float green, float blue, float alpha, CallbackInfo ci) {
        CapturedRenderingState.INSTANCE.setColorModulator(red, green, blue, alpha);
    }

    @Inject(method = "color(FFF)V", at = @At("HEAD"))
    private static void iris$captureColor3(float red, float green, float blue, CallbackInfo ci) {
        CapturedRenderingState.INSTANCE.setColorModulator(red, green, blue, 1.0f);
    }
}
