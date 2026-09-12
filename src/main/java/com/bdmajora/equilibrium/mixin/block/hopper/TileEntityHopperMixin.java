package com.bdmajora.equilibrium.mixin.block.hopper;

import com.bdmajora.equilibrium.common.hopper.HopperCacheHolder;
import com.bdmajora.equilibrium.common.hopper.HopperEntityLookup;
import com.bdmajora.equilibrium.common.hopper.HopperInventoryCache;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockHopper;
import net.minecraft.block.state.IBlockState;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.IHopper;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityHopper;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nullable;

// stops hoppers re-discovering the same two inventories several times a second
// every transfer attempt resolves the inventory the hopper faces and the one above it from scratch,
// and each resolution is a block read, a tile entity lookup that constructs a tile entity as a side
// effect if the block wants one and has none, and - whenever neither produces an inventory - an entity
// query over the block
// an idle hopper does all of this every single tick, because the eight-tick cooldown is only set after
// a *successful* transfer
// two idle hoppers side by side are therefore running six entity queries a tick between them, and a
// storage hall is running thousands, forever, to discover nothing
// each hopper gets two HopperInventoryCaches, one per side; what they may and may not remember is
// documented there, but in short a non-chest tile entity is cached against the identity of the block
// state that was present when it was found, chests are always re-resolved because forming a double
// chest changes no block state, and the entity fallback is not cached at all - it is skipped outright
// when the world provably holds no inventory entities
// getInventoryAtPosition is deliberately left alone: it is public, static, and called by droppers,
// dispensers and a great deal of mod code that has nowhere to hang a cache
// the two instance-scoped entry points below are where the hopper's own repetition actually lives
@Mixin(TileEntityHopper.class)
public abstract class TileEntityHopperMixin extends TileEntity implements IHopper, HopperCacheHolder {
    @Unique
    private HopperInventoryCache equilibrium$destination;

    @Unique
    private HopperInventoryCache equilibrium$source;

    // created on first use rather than in a field initialiser
    // Mixin can merge initialisers for fields a mixin adds, but it does so by rewriting the target's
    // constructors, and this target has several inherited ones
    // a null check on a field the hopper reads twice per tick is not worth being clever about
    @Override
    public HopperInventoryCache equilibrium$sourceCache() {
        if (this.equilibrium$source == null) {
            this.equilibrium$source = new HopperInventoryCache();
        }

        return this.equilibrium$source;
    }

    @Override
    public HopperInventoryCache equilibrium$destinationCache() {
        if (this.equilibrium$destination == null) {
            this.equilibrium$destination = new HopperInventoryCache();
        }

        return this.equilibrium$destination;
    }

    // Overwrite: the facing inventory through this hopper's cache
    @Nullable
    @Overwrite
    private IInventory getInventoryForHopperTransfer() {
        EnumFacing facing = BlockHopper.getFacing(this.getBlockMetadata());

        return this.equilibrium$destinationCache().get(this.getWorld(),
                this.getXPos() + facing.getXOffset(),
                this.getYPos() + facing.getYOffset(),
                this.getZPos() + facing.getZOffset());
    }

    // Overwrite: the inventory above through the cache when the hopper has one, else vanilla's lookup
    @Nullable
    @Overwrite
    public static IInventory getSourceInventory(IHopper hopper) {
        double x = hopper.getXPos();
        double y = hopper.getYPos() + 1.0D;
        double z = hopper.getZPos();

        if (hopper instanceof HopperCacheHolder) {
            return ((HopperCacheHolder) hopper).equilibrium$sourceCache().get(hopper.getWorld(), x, y, z);
        }

        return equilibrium$resolveUncached(hopper.getWorld(), x, y, z);
    }

    // [VanillaCopy] TileEntityHopper#getInventoryAtPosition, for hoppers that cannot cache
    // hopper minecarts reach this; they move, so a cache keyed on the block state at a fixed position
    // would be answering about wherever the minecart used to be
    // the only thing they gain here is HopperEntityLookup, which skips the entity query when it
    // provably has nothing to find - and that is exactly the query a minecart running along a rail
    // with nothing above it would otherwise repeat every tick
    @Unique
    @Nullable
    private static IInventory equilibrium$resolveUncached(World world, double x, double y, double z) {
        BlockPos pos = new BlockPos(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z));

        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        IInventory inventory = null;

        if (block.hasTileEntity(state)) {
            TileEntity tileEntity = world.getTileEntity(pos);

            if (tileEntity instanceof IInventory) {
                inventory = (IInventory) tileEntity;

                if (inventory instanceof TileEntityChest && block instanceof BlockChest) {
                    inventory = ((BlockChest) block).getContainer(world, pos, true);
                }
            }
        }

        return inventory != null ? inventory : HopperEntityLookup.findInventoryEntity(world, x, y, z);
    }
}
