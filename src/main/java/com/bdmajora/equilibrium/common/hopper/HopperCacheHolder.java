package com.bdmajora.equilibrium.common.hopper;

// A hopper caching the inventories on either side, implemented on TileEntityHopper by mixin so getSourceInventory can ask whether its IHopper has caches (minecarts do not)
public interface HopperCacheHolder {
    // The block above the hopper, which it pulls from.
    HopperInventoryCache equilibrium$sourceCache();

    // The block the hopper faces, which it pushes into.
    HopperInventoryCache equilibrium$destinationCache();
}
