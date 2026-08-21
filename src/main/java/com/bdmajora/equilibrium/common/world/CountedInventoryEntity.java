package com.bdmajora.equilibrium.common.world;

import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * Records which world, if any, currently counts this entity in its inventory-entity total.
 *
 * <p>Implemented on {@code Entity} by {@code mixin.util.data_storage}.
 *
 * <p>This exists because the count cannot be maintained safely from
 * {@code World.onEntityAdded}/{@code onEntityRemoved} alone. Those two are not a matched pair:
 * {@code World.loadEntities} only calls {@code onEntityAdded} when Forge's
 * {@code EntityJoinWorldEvent} was not cancelled, while {@code World.unloadEntities} queues every
 * entity in the chunk for {@code onEntityRemoved} regardless. A mod cancelling the join event for a
 * chest minecart would produce a decrement with no matching increment, and the count would read zero
 * while such an entity was still in the world.
 *
 * <p>That failure is silent and it breaks gameplay — hoppers would stop seeing chest minecarts — so
 * it must not be possible. Holding the world on the entity makes the accounting structural rather
 * than inferred:
 *
 * <ul>
 *   <li>a world only decrements for an entity that says <em>that</em> world counted it, so a
 *       decrement can never outrun an increment;
 *   <li>an entity that turns up in a second world without having left the first increments the second
 *       regardless, so the world it is actually in can never under-count.
 * </ul>
 *
 * <p>The one reachable error is a world counting an entity that abnormally left it, which costs
 * nothing but the vanilla entity query the count exists to skip.
 */
public interface CountedInventoryEntity {
    @Nullable
    World equilibrium$getCountedWorld();

    void equilibrium$setCountedWorld(@Nullable World world);
}
