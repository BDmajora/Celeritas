package com.bdmajora.extras.mixin.render.fog;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import com.bdmajora.extras.client.FogState;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Fog start and end distances.
 *
 * <p>Impetus reads {@code GL_FOG_START} and {@code GL_FOG_END} straight into its terrain and sky
 * shader uniforms, so both must stay finite with {@code start < end}. Writing {@code Float.MAX_VALUE}
 * to push fog "away" — the obvious way to express "no fog" — poisons the shader's fog math instead,
 * which is why turning fog off is handled by {@link EntityRendererFogMixin} clearing the enable bit
 * and this class leaves the values alone in that case.
 *
 * <p>Gameplay fog is exempt; see {@link FogState}.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererFogFalloffMixin {
    /** Vanilla's {@code GL_LINEAR} fog end, used to keep a custom start below it. */
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
