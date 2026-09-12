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

// Stops hoppers re-resolving the same two inventories every tick (the cooldown is only set after a SUCCESSFUL transfer, so idle hoppers run entity queries forever); two HopperInventoryCaches per hopper, and the public static getInventoryAtPosition is left alone for droppers and mod code
@Mixin(TileEntityHopper.class)
public abstract class TileEntityHopperMixin extends TileEntity implements IHopper, HopperCacheHolder {
    @Unique
    private HopperInventoryCache equilibrium$destination;

    @Unique
    private HopperInventoryCache equilibrium$source;

    // Created on first use rather than a field initialiser, since Mixin merges initialisers by rewriting constructors and this target has several inherited ones
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

    // [VanillaCopy] TileEntityHopper#getInventoryAtPosition for hoppers that cannot cache (minecarts move, so a position-keyed cache would be stale); they still gain HopperEntityLookup skipping the empty entity query
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
