package com.bdmajora.impetus.mixin.core.shader;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.entity.Render;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.bdmajora.impetus.iris.Iris;
import com.bdmajora.impetus.iris.pipeline.IrisRenderingPipeline;

@Mixin(Render.class)
public class RenderMixin {
    @ModifyExpressionValue(method = "doRenderShadowAndFire",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/settings/GameSettings;entityShadows:Z"))
    private boolean impetus$disableVanillaEntityShadowsWithShaderShadows(boolean entityShadows) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        return entityShadows && (pipeline == null || !pipeline.shouldDisableVanillaEntityShadows());
    }
}
