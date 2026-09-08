package com.bdmajora.extras.mixin.particle;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Skips the whole rain-splash pass rather than filtering particles one at a time
// addRainParticles raycasts per candidate position before spawning, so cancelling at the top avoids that search entirely
@Mixin(EntityRenderer.class)
public class EntityRendererRainSplashMixin {
    @Inject(method = "addRainParticles()V", at = @At("HEAD"), cancellable = true)
    private void impetus$addRainParticles(CallbackInfo ci) {
        ExtrasConfig.ParticleSettings settings = Extras.options().particle;
        if (!settings.all || !settings.rainSplash) {
            ci.cancel();
        }
    }
}
