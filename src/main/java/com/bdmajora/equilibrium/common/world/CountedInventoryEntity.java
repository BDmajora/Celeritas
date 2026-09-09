package com.bdmajora.equilibrium.common.world;

import net.minecraft.world.World;

import javax.annotation.Nullable;

// records which world, if any, currently counts this entity in its inventory-entity total
// implemented on Entity by mixin.util.data_storage
// this exists because the count cannot be maintained safely from World.onEntityAdded and
// World.onEntityRemoved alone - those two are not a matched pair: World.loadEntities only calls
// onEntityAdded when Forge's EntityJoinWorldEvent was not cancelled, while World.unloadEntities
// queues every entity in the chunk for onEntityRemoved regardless, so a mod cancelling the join
// event for a chest minecart would produce a decrement with no matching increment and the count
// would read zero while such an entity was still in the world
// that failure is silent and it breaks gameplay - hoppers would stop seeing chest minecarts - so it
// must not be possible; holding the world on the entity makes the accounting structural rather than
// inferred, in two directions:
//   a world only decrements for an entity that says *that* world counted it, so a decrement can
//   never outrun an increment
//   an entity that turns up in a second world without having left the first increments the second
//   regardless, so the world it is actually in can never under-count
// the one reachable error is a world counting an entity that abnormally left it, which costs nothing
// but the vanilla entity query the count exists to skip
public interface CountedInventoryEntity {
    @Nullable
    World equilibrium$getCountedWorld();

    void equilibrium$setCountedWorld(@Nullable World world);
}
