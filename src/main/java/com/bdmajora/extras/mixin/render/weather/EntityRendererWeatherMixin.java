package com.bdmajora.extras.mixin.render.weather;

import com.bdmajora.extras.Extras;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides falling rain and snow.
 *
 * <p>Only the draw pass; the weather itself still happens, so sounds, mob spawning and crop growth
 * are unaffected. Locking the weather outright is a separate option — see
 * {@code TimeWeatherOverride}.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererWeatherMixin {
    @Inject(method = "renderRainSnow", at = @At("HEAD"), cancellable = true)
    private void impetus$renderRainSnow(float partialTicks, CallbackInfo ci) {
        if (!Extras.options().detail.rainSnow) {
            ci.cancel();
        }
    }
}
