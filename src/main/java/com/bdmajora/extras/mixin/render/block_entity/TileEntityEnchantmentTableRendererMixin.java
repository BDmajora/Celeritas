package com.bdmajora.extras.mixin.render.block_entity;

import com.bdmajora.extras.Extras;
import net.minecraft.client.renderer.tileentity.TileEntityEnchantmentTableRenderer;
import net.minecraft.tileentity.TileEntityEnchantmentTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the enchanting table's animated floating book. */
@Mixin(TileEntityEnchantmentTableRenderer.class)
public class TileEntityEnchantmentTableRendererMixin {
    @Inject(
            method = "render(Lnet/minecraft/tileentity/TileEntityEnchantmentTable;DDDFIF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void impetus$render(TileEntityEnchantmentTable table, double x, double y, double z,
                                float partialTicks, int destroyStage, float alpha, CallbackInfo ci) {
        if (!Extras.options().render.enchantingTableBooks) {
            ci.cancel();
        }
    }
}
