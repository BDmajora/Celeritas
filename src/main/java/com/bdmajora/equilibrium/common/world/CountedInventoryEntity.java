package com.bdmajora.equilibrium.common.world;

import net.minecraft.world.World;

import javax.annotation.Nullable;

// Records which world currently counts this entity in its inventory-entity total, implemented on Entity by mixin
// onEntityAdded and onEntityRemoved are not a matched pair (a cancelled join event skips the add but not the
// remove), so the world is held on the entity to make the accounting structural rather than inferred
public interface CountedInventoryEntity {
    @Nullable
    World equilibrium$getCountedWorld();

    void equilibrium$setCountedWorld(@Nullable World world);
}
