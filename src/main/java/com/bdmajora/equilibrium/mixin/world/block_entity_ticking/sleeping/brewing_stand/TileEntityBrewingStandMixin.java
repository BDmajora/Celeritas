package com.bdmajora.equilibrium.mixin.world.block_entity_ticking.sleeping.brewing_stand;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityBrewingStand;
import net.minecraft.util.NonNullList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Stops idle brewing stands walking the entire brewing recipe registry every tick (Forge diverts canBrew() into BrewingRecipeRegistry): with no ingredient nothing can brew, but the rest of update still runs so bottle silhouettes stay in sync
@Mixin(TileEntityBrewingStand.class)
public abstract class TileEntityBrewingStandMixin {
    @Shadow
    private NonNullList<ItemStack> brewingItemStacks;

    // Private in the target so it cannot be shadowed as abstract; the body is never reached, and calls from here are not redirected since injectors only rewrite their target method
    @Shadow
    private boolean canBrew() {
        throw new AssertionError();
    }

    @Redirect(
            method = "update",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/TileEntityBrewingStand;canBrew()Z")
    )
    private boolean equilibrium$skipRecipeScanWhenEmpty(TileEntityBrewingStand stand) {
        // Slot 3 is the ingredient. BrewingRecipeRegistry.canBrew rejects an empty one outright.
        if (this.brewingItemStacks.get(3).isEmpty()) {
            return false;
        }

        return this.canBrew();
    }
}
