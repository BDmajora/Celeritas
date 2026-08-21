package com.bdmajora.equilibrium.mixin.util.data_storage;

import com.bdmajora.equilibrium.common.world.CountedInventoryEntity;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nullable;

/**
 * One reference per entity: the world that currently counts it as an inventory entity, if any.
 *
 * <p>See {@link CountedInventoryEntity} for why the count cannot be trusted without it. The field is
 * null for the overwhelming majority of entities, which are not inventories and are never counted.
 *
 * <p>It holds a world the entity is already holding through {@code Entity.world}, so it keeps nothing
 * alive that was not already reachable.
 */
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
