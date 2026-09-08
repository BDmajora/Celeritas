package com.bdmajora.extras.mixin.render.fog;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.client.FogState;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Turns fog off.
 *
 * <p>At the {@code RETURN} of {@code setupFog}, deliberately: vanilla's colour, mode and range are
 * all left correctly configured, and only the enable bit is cleared. Anything that reads that state
 * afterwards — Impetus' own terrain shader among them — therefore sees a consistent picture rather
 * than a half-configured one.
 *
 * <p>Gameplay fog is exempt; see {@link FogState}.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererFogMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void impetus$disableFog(int startCoords, float partialTicks, CallbackInfo ci) {
        if (!Extras.options().render.fog && !FogState.isGameplayFog()) {
            GlStateManager.disableFog();
        }
    }
}
