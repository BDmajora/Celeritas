package com.bdmajora.equilibrium.common.world;

// Whether a world holds any entity a hopper could pull from or push into, kept as a running count on World
// A zero count lets the hopper's entity query be skipped exactly, not approximately
public interface InventoryEntityTracker {
    // false only when the world provably contains no live IInventory entity
    // conservative in the direction that matters: a true answer costs nothing but the normal vanilla
    // query, whereas a wrong false would silently break chest minecarts
    boolean equilibrium$mayHaveInventoryEntities();
}
