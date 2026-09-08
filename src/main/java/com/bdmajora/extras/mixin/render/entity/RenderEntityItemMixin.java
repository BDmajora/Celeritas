package com.bdmajora.extras.mixin.render.entity;

import com.bdmajora.extras.Extras;
import net.minecraft.client.renderer.entity.RenderEntityItem;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * OptiFine's Dropped Items switch, as Fancy versus Fast.
 *
 * <p>Vanilla draws a pile of dropped items as up to five stacked copies of the model, scaled by
 * stack size. On Fast it draws one. The saving is real on a floor covered in drops after a mob farm
 * or a chest spill — a stack of 64 costs five full item models per frame otherwise.
 */
@Mixin(RenderEntityItem.class)
public class RenderEntityItemMixin {
    @Inject(method = "getModelCount", at = @At("HEAD"), cancellable = true)
    private void impetus$modelCount(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (!Extras.options().render.droppedItemsFancy) {
            cir.setReturnValue(1);
        }
    }
}
