package com.bdmajora.equilibrium.common.world;

import net.minecraft.world.World;

import javax.annotation.Nullable;

// Records which world counts this entity in its inventory-entity total (on Entity by mixin); onEntityAdded/onEntityRemoved are not a matched pair, so holding the world makes the accounting structural
public interface CountedInventoryEntity {
    @Nullable
    World equilibrium$getCountedWorld();

    void equilibrium$setCountedWorld(@Nullable World world);
}
