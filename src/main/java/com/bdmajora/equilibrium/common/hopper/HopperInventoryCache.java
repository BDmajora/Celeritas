package com.bdmajora.equilibrium.common.hopper;

import com.bdmajora.equilibrium.common.world.TileEntityAccess;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.block.state.IBlockState;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

// One hopper's memory of the inventory on one side, keyed on the block state that was there when resolved
// States are singletons, so identity answers "has this block changed" for the cost of a field load
// Chests are never cached since placing the other half of a double chest changes neither state; the entity fallback
// is never cached either, the whole query is skipped when the world provably holds no inventory entities
public final class HopperInventoryCache {
    // The state that was at the cached position when it was last resolved. Null means unresolved.
    private IBlockState state;

    // The tile entity found at that position, or null if there was none.
    private TileEntity tileEntity;

    // The inventory derived from #tileEntity. Null when the tile entity was not one.
    private IInventory inventory;

    // resolves the inventory at a position, reusing the previous answer when nothing relevant changed
    // mirrors TileEntityHopper.getInventoryAtPosition exactly, including the order the two sources are
    // tried in and the random pick among several inventory entities
    // the only differences are that the tile entity comes from a cache when it can, and that the
    // entity query is skipped when it provably has nothing to find
    // x/y/z are the double-precision coordinates vanilla passes, floored the same way it floors them
    @Nullable
    public IInventory get(World world, double x, double y, double z) {
        int blockX = net.minecraft.util.math.MathHelper.floor(x);
        int blockY = net.minecraft.util.math.MathHelper.floor(y);
        int blockZ = net.minecraft.util.math.MathHelper.floor(z);

        BlockPos pos = new BlockPos(blockX, blockY, blockZ);

        IInventory inventory = this.resolveTileEntityInventory(world, pos);

        if (inventory != null) {
            return inventory;
        }

        return HopperEntityLookup.findInventoryEntity(world, x, y, z);
    }

    // Resolves the inventory at a position, handling chests separately so they never enter the cache
    @Nullable
    private IInventory resolveTileEntityInventory(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        // Chests are re-resolved every time; see the class comment. Doing this before the cache check
        // rather than after keeps the cache from ever holding a chest, so there is no stale entry to
        // reason about if a chest replaces something else at this position.
        if (block instanceof BlockChest) {
            TileEntity tileEntity = this.lookup(world, pos);

            if (tileEntity instanceof TileEntityChest) {
                return ((BlockChest) block).getContainer(world, pos, true);
            }

            return tileEntity instanceof IInventory ? (IInventory) tileEntity : null;
        }

        if (this.state == state) {
            // The block is unchanged. The tile entity is only still valid if it has not been
            // invalidated out from under us — breaking and replacing a machine of the same kind
            // produces the same state but a different tile entity.
            if (this.tileEntity == null) {
                return null;
            }

            if (!this.tileEntity.isInvalid()) {
                return this.inventory;
            }
        }

        this.state = state;
        this.tileEntity = null;
        this.inventory = null;

        // Vanilla asks hasTileEntity before looking one up, and mods rely on that being asked.
        if (block.hasTileEntity(state)) {
            TileEntity tileEntity = this.lookup(world, pos);

            if (tileEntity instanceof IInventory) {
                this.tileEntity = tileEntity;
                this.inventory = (IInventory) tileEntity;
            }
        }

        return this.inventory;
    }

    // Prefers the non-creating lookup, so probing an empty position cannot force a tile entity into being
    @Nullable
    private TileEntity lookup(World world, BlockPos pos) {
        if (world instanceof TileEntityAccess) {
            return ((TileEntityAccess) world).equilibrium$getExistingTileEntity(pos);
        }

        return world.getTileEntity(pos);
    }

    // Drops the cached entry. Called when the hopper is invalidated or moves.
    public void clear() {
        this.state = null;
        this.tileEntity = null;
        this.inventory = null;
    }
}
