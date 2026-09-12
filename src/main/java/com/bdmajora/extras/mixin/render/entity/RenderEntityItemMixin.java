package com.bdmajora.extras.mixin.render.entity;

import com.bdmajora.extras.Extras;
import net.minecraft.client.renderer.entity.RenderEntityItem;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// OptiFine's Dropped Items switch, Fancy vs Fast: vanilla stacks up to five model copies by stack size, Fast draws one, a real saving on a floor covered in drops
@Mixin(RenderEntityItem.class)
public class RenderEntityItemMixin {
    @Inject(method = "getModelCount", at = @At("HEAD"), cancellable = true)
    private void impetus$modelCount(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (!Extras.options().render.droppedItemsFancy) {
            cir.setReturnValue(1);
        }
    }
}
