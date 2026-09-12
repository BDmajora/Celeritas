package com.bdmajora.extras.mixin.render.sky;

import com.bdmajora.extras.Extras;
import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hides the sky dome, horizon and void planes; cancelling renderSky takes sun, moon and stars with it (matches Sodium Extra), see RenderGlobalSunMoonMixin/RenderGlobalStarsMixin for keeping the sky
@Mixin(RenderGlobal.class)
public class RenderGlobalSkyMixin {
    @Inject(method = "renderSky(FI)V", at = @At("HEAD"), cancellable = true)
    private void impetus$renderSky(float partialTicks, int pass, CallbackInfo ci) {
        if (!Extras.options().detail.sky) {
            ci.cancel();
        }
    }
}
