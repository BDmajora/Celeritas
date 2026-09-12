package com.bdmajora.extras.mixin.render.weather;

import com.bdmajora.extras.Extras;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hides falling rain and snow in the draw pass only; weather still happens for sounds, spawning and crops, and locking it is TimeWeatherOverride's job
@Mixin(EntityRenderer.class)
public class EntityRendererWeatherMixin {
    @Inject(method = "renderRainSnow", at = @At("HEAD"), cancellable = true)
    private void impetus$renderRainSnow(float partialTicks, CallbackInfo ci) {
        if (!Extras.options().detail.rainSnow) {
            ci.cancel();
        }
    }
}
