package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.iris.Iris;
import com.bdmajora.impetus.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.IBakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The held/dropped-item half of the enchantment glint, the counterpart to {@link LayerArmorBaseGlintMixin}. OptiFine
 * brackets the same method ({@code func_191966_a} in its snapshot, {@code renderEffect} once MCP named it) with
 * {@code ShadersRender.renderEnchantedGlintBegin()} / {@code renderEnchantedGlintEnd()}.
 * <p>
 * OptiFine additionally gates on its own {@code renderItemGui} field, which vanilla does not have. No substitute is
 * needed: {@link IrisRenderingPipeline#beginArmorGlint()} already no-ops unless world rendering is active, and GUI
 * inventory items draw outside that window.
 * <p>
 * Both names are listed with {@code require = 0} because which one resolves depends on the mapping snapshot; the
 * unmatched selector is simply skipped rather than failing the mixin.
 */
@Mixin(RenderItem.class)
public class RenderItemGlintMixin {
    @Inject(method = {"renderEffect", "func_191966_a"}, at = @At("HEAD"), require = 0)
    private void impetus$beginItemGlint(IBakedModel model, CallbackInfo ci) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.beginArmorGlint();
        }
    }

    @Inject(method = {"renderEffect", "func_191966_a"}, at = @At("RETURN"), require = 0)
    private void impetus$endItemGlint(IBakedModel model, CallbackInfo ci) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.endArmorGlint();
        }
    }
}
