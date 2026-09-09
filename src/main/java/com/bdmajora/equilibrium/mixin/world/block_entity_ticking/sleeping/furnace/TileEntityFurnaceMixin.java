package com.bdmajora.equilibrium.mixin.world.block_entity_ticking.sleeping.furnace;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.NonNullList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// lets an idle furnace stop ticking
// a furnace that is unlit, has nothing part-cooked, and is missing either its fuel or its input
// cannot do anything: vanilla's own branches all fall through, the lit-state comparison finds no
// change, and nothing is marked dirty - the tick is a pure no-op that every furnace in every loaded
// chunk performs twenty times a second
// each clause of the guard corresponds to a branch that would otherwise have work to do:
//   furnaceBurnTime == 0, because a lit furnace must burn down and may finish smelting
//   cookTime == 0, because an unlit furnace with progress on the clock decays it by two per tick,
//   which is visible on the arrow and must keep happening
//   either input or fuel empty, because with both present and unlit vanilla may light the furnace
//   this tick
// nothing needs to wake it: the guard is four field reads and two emptiness checks evaluated on the
// tick itself, so the moment a hopper drops fuel in, the very next tick sees it - cheaper and
// considerably harder to get wrong than a subscription system, which is what Lithium needs on
// versions where the equivalent predicate is expensive to evaluate
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
