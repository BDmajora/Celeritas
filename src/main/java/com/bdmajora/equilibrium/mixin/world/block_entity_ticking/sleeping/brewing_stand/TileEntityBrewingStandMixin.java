package com.bdmajora.equilibrium.mixin.world.block_entity_ticking.sleeping.brewing_stand;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityBrewingStand;
import net.minecraft.util.NonNullList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops idle brewing stands walking the entire brewing recipe registry once per tick.
 *
 * <p>{@code update} calls {@code canBrew()} unconditionally, and on Forge that call is diverted
 * straight into {@code BrewingRecipeRegistry.canBrew}, which iterates every registered brewing recipe
 * — vanilla's plus whatever every mod in the pack has added. A modpack with a few hundred recipes and
 * a few dozen brewing stands is scanning tens of thousands of recipes a second to conclude, every
 * time, that an empty stand cannot brew anything.
 *
 * <p>The guard is the first thing the registry itself checks: with no ingredient in the top slot,
 * nothing can be brewed. Answering that here rather than after a full scan gives the same answer.
 *
 * <p>The rest of {@code update} still runs, which matters — it is what keeps the bottle silhouettes
 * on the block in sync with the inventory, and skipping the whole tick would leave a stand that was
 * filled while idle showing empty until something else touched it.
 */
@Mixin(TileEntityBrewingStand.class)
public abstract class TileEntityBrewingStandMixin {
    @Shadow
    private NonNullList<ItemStack> brewingItemStacks;

    // Private in the target, so it cannot be shadowed as abstract; the body is never reached.
    // Calls to it from here are not themselves redirected — injectors only rewrite their target
    // method's bytecode, so this reaches the real implementation rather than recursing.
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
