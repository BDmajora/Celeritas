package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.iris.pipeline.VanillaFeatureToggles;
import net.minecraft.client.renderer.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The {@code underwaterOverlay} shaders.properties toggle: packs that shade being underwater in their composite
 * chain (Complementary, Photon) turn off vanilla's screen-space water texture.
 *
 * @see VanillaFeatureToggles
 */
@Mixin(ItemRenderer.class)
public class VanillaOverlayToggleMixin {
    @Inject(method = "renderWaterOverlayTexture", at = @At("HEAD"), cancellable = true, require = 0)
    private void impetus$suppressUnderwaterOverlay(float partialTicks, CallbackInfo ci) {
        if (!VanillaFeatureToggles.shouldRenderUnderwaterOverlay()) {
            ci.cancel();
        }
    }
}
