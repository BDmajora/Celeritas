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

// per-world scratch data, currently one thing: how many entities in this world are inventories
// maintained from onEntityAdded and onEntityRemoved, which every entity passes through when it joins
// or leaves a world - chunk load and unload included, and both WorldServer and WorldClient call up
// to these when they override them
// the count is guarded by a flag on the entity rather than trusted to the symmetry of those two
// calls, because they are not symmetric: World.loadEntities skips onEntityAdded when Forge's join
// event is cancelled, while World.unloadEntities queues everything for onEntityRemoved regardless
// without the flag a cancelled join would leave the count one lower than the truth, and a count that
// reads zero while a chest minecart exists would make hoppers quietly stop seeing it - see
// CountedInventoryEntity
// with the flag, the only reachable error is counting too many, which costs nothing but the vanilla
// entity query the count exists to skip
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

        // Already counted by this world: adding twice must not count twice. Counted by another
        // world: take it over anyway, leaving the other world over-counting rather than letting
        // this one under-count.
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

        // Only decrement for an entity this world actually counted. Without that check a removal
        // with no matching addition would drive the count below the truth.
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
