package com.bdmajora.equilibrium.mixin.util.data_storage;

import com.bdmajora.equilibrium.common.world.CountedInventoryEntity;
import com.bdmajora.equilibrium.common.world.InventoryEntityTracker;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.IInventory;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Per-world count of inventory entities maintained from onEntityAdded/onEntityRemoved, guarded by a flag on the entity since a cancelled Forge join event skips the add but not the remove; the only reachable error is over-counting, which just costs the vanilla query
@Mixin(World.class)
public abstract class WorldMixin implements InventoryEntityTracker {
    @Unique
    private int equilibrium$inventoryEntities;

    @Inject(method = "onEntityAdded", at = @At("HEAD"))
    private void equilibrium$countInventoryEntityAdded(Entity entity, CallbackInfo ci) {
        if (!(entity instanceof IInventory) || !(entity instanceof CountedInventoryEntity)) {
            return;
        }

        CountedInventoryEntity counted = (CountedInventoryEntity) entity;

        // Already counted by this world: adding twice must not count twice; counted by another world: take it over anyway, leaving the other over-counting rather than this one under-counting
        if (counted.equilibrium$getCountedWorld() != (Object) this) {
            counted.equilibrium$setCountedWorld((World) (Object) this);
            this.equilibrium$inventoryEntities++;
        }
    }

    @Inject(method = "onEntityRemoved", at = @At("HEAD"))
    private void equilibrium$countInventoryEntityRemoved(Entity entity, CallbackInfo ci) {
        if (!(entity instanceof CountedInventoryEntity)) {
            return;
        }

        CountedInventoryEntity counted = (CountedInventoryEntity) entity;

        // Only decrement for an entity this world actually counted, or a removal with no matching addition drives the count below the truth
        if (counted.equilibrium$getCountedWorld() == (Object) this) {
            counted.equilibrium$setCountedWorld(null);
            this.equilibrium$inventoryEntities--;
        }
    }

    @Override
    public boolean equilibrium$mayHaveInventoryEntities() {
        return this.equilibrium$inventoryEntities > 0;
    }
}
