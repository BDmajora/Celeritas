package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.iris.pipeline.VanillaFeatureToggles;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The {@code vignette} shaders.properties toggle. Complementary and Photon both set it false: their composite chain
 * already applies its own vignette, and vanilla's would be composited on top of the finished tonemapped image.
 *
 * @see VanillaFeatureToggles
 */
@Mixin(GuiIngame.class)
public class VanillaVignetteToggleMixin {
    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true, require = 0)
    private void impetus$suppressVignette(float lightLevel, ScaledResolution scaledRes, CallbackInfo ci) {
        if (!VanillaFeatureToggles.shouldRenderVignette()) {
            ci.cancel();
        }
    }
}
