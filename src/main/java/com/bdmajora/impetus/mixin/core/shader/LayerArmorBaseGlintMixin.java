package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.umbra.Umbra;
import com.bdmajora.impetus.umbra.pipeline.UmbraRenderingPipeline;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// routes the armour enchantment glint through gbuffers_armor_glint
// see UmbraRenderingPipeline#beginArmorGlint() for why the glint needs its own program
// the anchors mirror OptiFine exactly: its patched LayerArmorBase.renderEnchantedGlint wraps the
// whole method body in ShadersRender.renderEnchantedGlintBegin() / renderEnchantedGlintEnd(), so
// HEAD/RETURN here cover the same span
// the method is public static, and both of vanilla's call sites - the enchanted-item branch and the
// legacy isItemEnchanted branch - go through it
@Mixin(LayerArmorBase.class)
public class LayerArmorBaseGlintMixin {
    @Inject(method = "renderEnchantedGlint", at = @At("HEAD"), require = 0)
    private static void impetus$beginArmorGlint(RenderLivingBase<?> renderer, EntityLivingBase entity, ModelBase model,
                                                float limbSwing, float limbSwingAmount, float partialTicks,
                                                float ageInTicks, float netHeadYaw, float headPitch, float scale,
                                                CallbackInfo ci) {
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.beginArmorGlint();
        }
    }

    @Inject(method = "renderEnchantedGlint", at = @At("RETURN"), require = 0)
    private static void impetus$endArmorGlint(RenderLivingBase<?> renderer, EntityLivingBase entity, ModelBase model,
                                              float limbSwing, float limbSwingAmount, float partialTicks,
                                              float ageInTicks, float netHeadYaw, float headPitch, float scale,
                                              CallbackInfo ci) {
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.endArmorGlint();
        }
    }
}
