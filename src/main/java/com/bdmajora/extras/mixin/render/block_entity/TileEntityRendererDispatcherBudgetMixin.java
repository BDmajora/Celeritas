package com.bdmajora.extras.mixin.render.block_entity;

import com.bdmajora.extras.client.budget.RenderBudgetController;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Render-budget skipping for block entity special renderers; the three-argument render is the per-block-entity entry both the world renderer and Umbra's shadow pass go through, before vanilla's own 64-block cutoff
@Mixin(TileEntityRendererDispatcher.class)
public class TileEntityRendererDispatcherBudgetMixin {
    @Inject(
            method = "render(Lnet/minecraft/tileentity/TileEntity;FI)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void impetus$budgetBlockEntity(TileEntity blockEntity, float partialTicks, int destroyStage, CallbackInfo ci) {
        if (RenderBudgetController.shouldCullBlockEntity(blockEntity)) {
            ci.cancel();
        }
    }
}
