package com.bdmajora.equilibrium.common.hopper;

import com.bdmajora.equilibrium.common.world.InventoryEntityTracker;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.IInventory;
import net.minecraft.util.EntitySelectors;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The entity half of a hopper's inventory search, with the case that dominates it removed.
 *
 * <p>When there is no inventory tile entity at the target position, vanilla queries the world for
 * entities that implement {@code IInventory} — chest and hopper minecarts, and whatever mods add. It
 * is the right behaviour and it is also, for the great majority of hoppers, a query that will never
 * find anything and is run several times a second forever.
 *
 * <p>If the world holds no live inventory entity at all, the query cannot return one. That is not a
 * heuristic: {@code World#onEntityAdded} and {@code onEntityRemoved} are the only two ways an entity
 * enters or leaves a world, chunk loading included, and the count behind
 * {@link InventoryEntityTracker} is maintained from both. When it says zero, an empty list is the
 * same answer vanilla would have spent a chunk scan arriving at.
 *
 * <p>When the count is non-zero the vanilla query runs unchanged, including its random pick among
 * several candidates — which is observable, because two chest minecarts in one block make which one
 * a hopper drains a coin flip, and some contraptions are built on it.
 */
public final class HopperEntityLookup {
    private HopperEntityLookup() {
    }

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
