package com.bdmajora.equilibrium.mixin.alloc.deep_passengers;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Stops passenger collection allocating a hash map for entities that carry nobody.
 *
 * <p>{@code getRecursivePassengers} is called from the player list on every dimension change, from
 * horses and llamas every tick they are ridden, and from a scattering of mod code that asks it of
 * arbitrary entities. Vanilla starts by allocating a {@link java.util.HashSet} — which is a wrapper
 * around a fresh {@link java.util.HashMap} with its own table — and then, for the overwhelming
 * majority of entities, puts nothing in it.
 *
 * <p>Two changes. The empty case returns without allocating anything at all, and the non-empty case
 * uses a {@link ReferenceOpenHashSet}, which is one array rather than a table of nodes. Reference
 * identity is the right comparison here — entities do not override {@code equals}, so vanilla's set
 * was already de-duplicating by identity, just through {@code Object.equals}.
 *
 * <p>The returned collection is still mutable, deliberately. Vanilla's was, and returning an
 * immutable empty collection would turn a mod that adds to the result into a crash rather than a
 * no-op.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Shadow
    public abstract List<Entity> getPassengers();

    // Private in the target, so it cannot be shadowed as abstract; the body is never reached.
    @Shadow
    private <T extends Entity> void getRecursivePassengersByType(Class<T> entityClass, Set<T> theSet) {
        throw new AssertionError();
    }

    /**
     * @author JellySquid
     * @reason Avoid allocating a hash set for the common case of no passengers
     */
    @Overwrite
    public Collection<Entity> getRecursivePassengers() {
        Set<Entity> passengers = new ReferenceOpenHashSet<>(this.getPassengers().isEmpty() ? 0 : 4);

        this.getRecursivePassengersByType(Entity.class, passengers);

        return passengers;
    }

    /**
     * @author JellySquid
     * @reason Avoid allocating a hash set for the common case of no passengers
     */
    @Overwrite
    public <T extends Entity> Collection<T> getRecursivePassengersByType(Class<T> entityClass) {
        Set<T> passengers = new ReferenceOpenHashSet<>(this.getPassengers().isEmpty() ? 0 : 4);

        this.getRecursivePassengersByType(entityClass, passengers);

        return passengers;
    }
}
