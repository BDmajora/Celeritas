package com.bdmajora.equilibrium.common.world;

// whether a world contains any entity that a hopper could pull from or push into
// implemented on World by mixin.util.data_storage, which keeps a running count as entities are added
// and removed
// the point of the count is a single exact fast path: TileEntityHopper.getInventoryAtPosition falls
// back to World#getEntitiesInAABBexcluding(..., EntitySelectors.HAS_INVENTORY) whenever there is no
// inventory tile entity at the target position, and that is the normal case - a hopper with nothing
// above it runs that query on every transfer attempt, forever
// when the count is zero the query provably returns an empty list, so skipping it is not an
// approximation; it is the same answer, arrived at without touching the chunk
// EntitySelectors.HAS_INVENTORY accepts an entity when it is an IInventory and alive, which in
// vanilla only chest and hopper minecarts satisfy, plus whatever mods add - the vast majority of
// worlds hold none at any given moment
public interface InventoryEntityTracker {
    // false only when the world provably contains no live IInventory entity
    // conservative in the direction that matters: a true answer costs nothing but the normal vanilla
    // query, whereas a wrong false would silently break chest minecarts
    boolean equilibrium$mayHaveInventoryEntities();
}
