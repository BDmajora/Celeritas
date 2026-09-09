package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.umbra.Umbra;
import com.bdmajora.impetus.umbra.pipeline.UmbraRenderingPipeline;
import net.minecraft.client.renderer.entity.layers.LayerSpiderEyes;
import net.minecraft.entity.monster.EntitySpider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// routes the spider's glowing-eyes overlay through gbuffers_spidereyes
// see UmbraRenderingPipeline#beginEyes() for why this matters: without it the layer inherits
// gbuffers_entities, and vanilla's out-of-range full-bright lightmap sentinel turns two eye texels
// into a screen-filling bloom flare
// the anchor is vanilla's GlStateManager.color(1, 1, 1, 1) - it sits after the blend mode and the
// lightmap sentinel have been set and immediately before the model draw, so the program's blend
// override and the corrected lightmap coordinate are the last writes to win
// all three eyes layers share that shape
@Mixin(LayerSpiderEyes.class)
public class LayerSpiderEyesMixin {
    @Inject(method = "doRenderLayer(Lnet/minecraft/entity/monster/EntitySpider;FFFFFFF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;color(FFFF)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private void impetus$beginEyes(EntitySpider entity, float limbSwing, float limbSwingAmount, float partialTicks,
                                   float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.beginEyes();
        }
    }

    @Inject(method = "doRenderLayer(Lnet/minecraft/entity/monster/EntitySpider;FFFFFFF)V",
            at = @At("RETURN"), require = 0)
    private void impetus$endEyes(EntitySpider entity, float limbSwing, float limbSwingAmount, float partialTicks,
                                 float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.endEyes();
        }
    }
}
