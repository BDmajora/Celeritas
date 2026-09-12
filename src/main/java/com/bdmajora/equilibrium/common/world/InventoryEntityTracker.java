package com.bdmajora.equilibrium.common.world;

// Whether a world holds any entity a hopper could pull from or push into, kept as a running count on World so the entity query can be skipped exactly
public interface InventoryEntityTracker {
    // false only when the world provably has no live IInventory entity; a wrong true costs the normal query, a wrong false silently breaks chest minecarts
    boolean equilibrium$mayHaveInventoryEntities();
}
