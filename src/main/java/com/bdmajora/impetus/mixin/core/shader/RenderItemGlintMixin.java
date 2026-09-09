package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.umbra.Umbra;
import com.bdmajora.impetus.umbra.pipeline.UmbraRenderingPipeline;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.IBakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the held/dropped-item half of the enchantment glint, the counterpart to LayerArmorBaseGlintMixin
// OptiFine brackets the same method (func_191966_a in its snapshot, renderEffect once MCP named it)
// with ShadersRender.renderEnchantedGlintBegin() / renderEnchantedGlintEnd()
// OptiFine additionally gates on its own renderItemGui field, which vanilla does not have; no
// substitute is needed here because UmbraRenderingPipeline#beginArmorGlint() already no-ops unless
// world rendering is active, and GUI inventory items draw outside that window
// both names are listed with require = 0 because which one resolves depends on the mapping snapshot,
// and the unmatched selector is simply skipped rather than failing the mixin
@Mixin(RenderItem.class)
public class RenderItemGlintMixin {
    @Inject(method = {"renderEffect", "func_191966_a"}, at = @At("HEAD"), require = 0)
    private void impetus$beginItemGlint(IBakedModel model, CallbackInfo ci) {
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.beginArmorGlint();
        }
    }

    @Inject(method = {"renderEffect", "func_191966_a"}, at = @At("RETURN"), require = 0)
    private void impetus$endItemGlint(IBakedModel model, CallbackInfo ci) {
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.endArmorGlint();
        }
    }
}
