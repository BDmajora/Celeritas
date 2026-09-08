package com.bdmajora.extras.mixin.render.fog;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import com.bdmajora.extras.client.FogState;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Fog start and end distances; Impetus reads GL_FOG_START/GL_FOG_END into shader uniforms, so both must stay finite with start < end
// Writing Float.MAX_VALUE to fake "no fog" would poison the shader math instead, so disabling fog is left to EntityRendererFogMixin
// Gameplay fog is exempt; see FogState
@Mixin(EntityRenderer.class)
public class EntityRendererFogFalloffMixin {
    // Vanilla's GL_LINEAR fog end, used to keep a custom start below it
    @Shadow
    private float farPlaneDistance;

    @ModifyArg(
            method = "setupFog",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;setFogStart(F)V"),
            index = 0
    )
    private float impetus$fogStart(float original) {
        ExtrasConfig.RenderSettings settings = Extras.options().render;

        if (FogState.isGameplayFog() || !settings.fog) {
            return original;
        }

        float startFraction = settings.fogStart / 100.0F;

        if (settings.fogDistance > 0) {
            float end = (settings.fogDistance + 1) * 16.0F;
            return Math.min(settings.fogDistance * 16.0F * startFraction, end - 0.5F);
        }

        return Math.min(original * startFraction, this.farPlaneDistance - 0.5F);
    }

    @ModifyArg(
            method = "setupFog",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;setFogEnd(F)V"),
            index = 0
    )
    private float impetus$fogEnd(float original) {
        ExtrasConfig.RenderSettings settings = Extras.options().render;

        if (FogState.isGameplayFog() || !settings.fog) {
            return original;
        }

        if (settings.fogDistance > 0) {
            return (settings.fogDistance + 1) * 16.0F;
        }

        return original;
    }
}
