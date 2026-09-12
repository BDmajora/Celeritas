package com.bdmajora.equilibrium.common.hopper;

// A hopper that caches the inventories on either side, implemented on TileEntityHopper by mixin
// Exists so the static getSourceInventory can ask whether the IHopper it was handed has caches; minecarts do not
public interface HopperCacheHolder {
    // The block above the hopper, which it pulls from.
    HopperInventoryCache equilibrium$sourceCache();

    // The block the hopper faces, which it pushes into.
    HopperInventoryCache equilibrium$destinationCache();
}
