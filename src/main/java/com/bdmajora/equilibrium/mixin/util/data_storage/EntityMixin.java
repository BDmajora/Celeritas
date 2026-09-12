package com.bdmajora.equilibrium.mixin.util.data_storage;

import com.bdmajora.equilibrium.common.world.CountedInventoryEntity;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nullable;

// The world currently counting this entity as an inventory entity (see CountedInventoryEntity); null for nearly every entity, and it keeps nothing alive not already reachable via Entity.world
@Mixin(Entity.class)
public abstract class EntityMixin implements CountedInventoryEntity {
    @Unique
    private World equilibrium$countedWorld;

    @Nullable
    @Override
    public World equilibrium$getCountedWorld() {
        return this.equilibrium$countedWorld;
    }

    @Override
    public void equilibrium$setCountedWorld(@Nullable World world) {
        this.equilibrium$countedWorld = world;
    }
}
