package com.bdmajora.equilibrium.common.world;

/**
 * Whether a world contains any entity that a hopper could pull from or push into.
 *
 * <p>Implemented on {@code World} by {@code mixin.util.data_storage}, which keeps a running count as
 * entities are added and removed.
 *
 * <p>The point of the count is a single exact fast path. {@code TileEntityHopper.getInventoryAtPosition}
 * falls back to {@code World#getEntitiesInAABBexcluding(..., EntitySelectors.HAS_INVENTORY)} whenever
 * there is no inventory tile entity at the target position, and that is the normal case: a hopper with
 * nothing above it runs that query on every transfer attempt, forever. When the count is zero the
 * query provably returns an empty list, so skipping it is not an approximation — it is the same
 * answer, arrived at without touching the chunk.
 *
 * <p>{@code EntitySelectors.HAS_INVENTORY} accepts an entity when it is an {@code IInventory} and
 * alive. Only chest and hopper minecarts satisfy that in vanilla, plus whatever mods add; the vast
 * majority of worlds hold none at any given moment.
 */
public interface InventoryEntityTracker {
    /**
     * False only when the world provably contains no live {@code IInventory} entity.
     *
     * <p>Conservative in the direction that matters: a true answer costs nothing but the normal
     * vanilla query, whereas a wrong false would silently break chest minecarts.
     */
    boolean equilibrium$mayHaveInventoryEntities();
}
