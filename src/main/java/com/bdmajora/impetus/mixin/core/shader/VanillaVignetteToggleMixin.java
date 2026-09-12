package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.umbra.pipeline.VanillaFeatureToggles;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The "vignette" shaders.properties toggle via VanillaFeatureToggles; Complementary and Photon apply their own in the composite chain, and vanilla's would composite over the tonemapped image
@Mixin(GuiIngame.class)
public class VanillaVignetteToggleMixin {
    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true, require = 0)
    private void impetus$suppressVignette(float lightLevel, ScaledResolution scaledRes, CallbackInfo ci) {
        if (!VanillaFeatureToggles.shouldRenderVignette()) {
            ci.cancel();
        }
    }
}
