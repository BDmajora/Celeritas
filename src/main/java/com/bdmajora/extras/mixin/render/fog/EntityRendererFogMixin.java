package com.bdmajora.extras.mixin.render.fog;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.client.FogState;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Turns fog off at setupFog's RETURN so colour/mode/range stay configured and only the enable bit clears, keeping the state consistent for Impetus' terrain shader; gameplay fog exempt, see FogState
@Mixin(EntityRenderer.class)
public class EntityRendererFogMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void impetus$disableFog(int startCoords, float partialTicks, CallbackInfo ci) {
        if (!Extras.options().render.fog && !FogState.isGameplayFog()) {
            GlStateManager.disableFog();
        }
    }
}
