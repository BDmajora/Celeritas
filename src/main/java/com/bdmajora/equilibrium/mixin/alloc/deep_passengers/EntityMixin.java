package com.bdmajora.equilibrium.mixin.alloc.deep_passengers;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Collection;
import java.util.List;
import java.util.Set;

// Avoids allocating a HashMap-backed set for an entity with no passengers, called every tick for ridden mounts; ReferenceOpenHashSet is fine since Entity does not override equals
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Shadow
    public abstract List<Entity> getPassengers();

    // Private in the target, so it cannot be shadowed as abstract; the body is never reached.
    @Shadow
    private <T extends Entity> void getRecursivePassengersByType(Class<T> entityClass, Set<T> theSet) {
        throw new AssertionError();
    }

    // Avoid allocating a hash set for the common case of no passengers
    @Overwrite
    public Collection<Entity> getRecursivePassengers() {
        Set<Entity> passengers = new ReferenceOpenHashSet<>(this.getPassengers().isEmpty() ? 0 : 4);

        this.getRecursivePassengersByType(Entity.class, passengers);

        return passengers;
    }

    // Avoid allocating a hash set for the common case of no passengers
    @Overwrite
    public <T extends Entity> Collection<T> getRecursivePassengersByType(Class<T> entityClass) {
        Set<T> passengers = new ReferenceOpenHashSet<>(this.getPassengers().isEmpty() ? 0 : 4);

        this.getRecursivePassengersByType(entityClass, passengers);

        return passengers;
    }
}
