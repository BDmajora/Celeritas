package com.bdmajora.equilibrium.common.hopper;

import com.bdmajora.equilibrium.common.world.InventoryEntityTracker;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.IInventory;
import net.minecraft.util.EntitySelectors;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

// The entity half of a hopper's inventory search, skipped entirely when the world holds no inventory
// entity at all; onEntityAdded/onEntityRemoved are the only ways one enters or leaves, so a zero count
// is exact rather than a heuristic
// With a non-zero count the vanilla query runs unchanged, including its observable random pick
public final class HopperEntityLookup {
    // Static-only
    private HopperEntityLookup() {
    }

    // Null when nothing is there, matching what the vanilla branch returns for an empty candidate list
    @Nullable
    public static IInventory findInventoryEntity(World world, double x, double y, double z) {
        if (world instanceof InventoryEntityTracker
                && !((InventoryEntityTracker) world).equilibrium$mayHaveInventoryEntities()) {
            return null;
        }

        // [VanillaCopy] TileEntityHopper#getInventoryAtPosition, entity branch.
        List<Entity> candidates = world.getEntitiesInAABBexcluding(null,
                new AxisAlignedBB(x - 0.5D, y - 0.5D, z - 0.5D, x + 0.5D, y + 0.5D, z + 0.5D),
                EntitySelectors.HAS_INVENTORY);

        if (candidates.isEmpty()) {
            return null;
        }

        return (IInventory) candidates.get(world.rand.nextInt(candidates.size()));
    }
}
