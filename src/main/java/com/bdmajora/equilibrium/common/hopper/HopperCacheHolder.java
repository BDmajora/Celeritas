package com.bdmajora.equilibrium.common.hopper;

/**
 * A hopper that remembers the inventories on either side of it.
 *
 * <p>Implemented on {@code TileEntityHopper} by {@code mixin.block.hopper}.
 *
 * <p>This exists so that {@code getSourceInventory} — which is static and takes an {@code IHopper},
 * an interface hopper minecarts also implement — can ask whether the hopper it was handed is one that
 * has caches, without depending on Mixin rewriting a reference to the mixin class inside a static
 * method. A minecart moves, so a cache keyed on the block state at a fixed position would be
 * meaningless for one; it takes the uncached path.
 */
public interface HopperCacheHolder {
    /** The block above the hopper, which it pulls from. */
    HopperInventoryCache equilibrium$sourceCache();

    /** The block the hopper faces, which it pushes into. */
    HopperInventoryCache equilibrium$destinationCache();
}
