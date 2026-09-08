package com.bdmajora.extras.mixin.render.fog;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.client.FogState;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Turns fog off at the RETURN of setupFog, deliberately: colour/mode/range stay correctly configured, only the enable bit is cleared,
// so anything reading that state afterwards (Impetus' terrain shader included) sees a consistent picture
// Gameplay fog is exempt; see FogState
@Mixin(EntityRenderer.class)
public class EntityRendererFogMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void impetus$disableFog(int startCoords, float partialTicks, CallbackInfo ci) {
        if (!Extras.options().render.fog && !FogState.isGameplayFog()) {
            GlStateManager.disableFog();
        }
    }
}
