package com.bdmajora.equilibrium.mixin.world.block_entity_ticking.sleeping.furnace;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.NonNullList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Lets an idle furnace skip its tick: unlit (furnaceBurnTime == 0), no progress to decay (cookTime == 0), and missing input or fuel means every vanilla branch is a no-op; the guard is re-evaluated each tick so a hopper dropping fuel in is seen next tick without a subscription system
@Mixin(TileEntityFurnace.class)
public abstract class TileEntityFurnaceMixin {
    @Shadow
    private NonNullList<ItemStack> furnaceItemStacks;

    @Shadow
    private int furnaceBurnTime;

    @Shadow
    private int cookTime;

    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void equilibrium$sleepWhileIdle(CallbackInfo ci) {
        if (this.furnaceBurnTime != 0 || this.cookTime != 0) {
            return;
        }

        // Slot 0 is the input, slot 1 the fuel.
        if (!this.furnaceItemStacks.get(0).isEmpty() && !this.furnaceItemStacks.get(1).isEmpty()) {
            return;
        }

        ci.cancel();
    }
}
