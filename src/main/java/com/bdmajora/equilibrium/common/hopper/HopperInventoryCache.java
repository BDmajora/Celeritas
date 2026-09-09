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

// one hopper's memory of the inventory on one of its two sides
// a hopper resolves the inventory above it and the one it faces on every transfer attempt, which is
// every eight ticks when it is moving items and *every tick* when it is not, because the cooldown
// only gets set on a successful transfer
// resolving means a block read, a tile entity lookup that may construct a tile entity as a side
// effect, and - when neither turns up an inventory - an entity query over the block
// a row of idle hoppers is the most reliable source of tick lag on this version, and almost all of it
// is this
// cached: the tile entity, keyed on the block state that was there when it was resolved
// block states are singletons, so an identity comparison answers "has this block changed" exactly and
// for the cost of a field load, and a tile entity that has been invalidated fails the check
// separately, which covers removal without a state change
// not cached: anything about chests, because BlockChest.getContainer inspects all four horizontal
// neighbours to decide whether this is half of a double chest and placing the other half does not
// change either chest's block state - a cache keyed on state would keep handing back a single-chest
// view of what is now a double chest, so chests take the vanilla path every time
// not cached: the entity fallback, because an inventory entity can move into or out of the block
// without anything nearby changing; instead of caching it the whole query is skipped when the world
// provably holds no inventory entities at all - see InventoryEntityTracker
// the cache lives on the hopper and is never shared, so it needs no synchronisation, and each hopper
// has two: one for the block above it and one for the block it faces
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
