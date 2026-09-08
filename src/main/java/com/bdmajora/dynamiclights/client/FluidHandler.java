package com.bdmajora.dynamiclights.client;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fml.common.Loader;

/**
 * Whether a position is inside a fluid, which is what "extinguish when underwater" turns on.
 *
 * <p>Asks about the block's material and Forge's {@link IFluidBlock}, so modded fluids count as well
 * as water and lava.
 */
public final class FluidHandler {
    private static final boolean FLUIDLOGGING = Loader.isModLoaded("fluidlogged_api");

    private FluidHandler() {
    }

    /** Whether the entity's eyes are inside a fluid at the current render partial tick. */
    public static boolean isFluid(Entity entity) {
        float partialTicks = Minecraft.getMinecraft().getRenderPartialTicks();
        return isFluid(entity.world, new BlockPos(entity.getPositionEyes(partialTicks)));
    }

    public static boolean isFluid(IBlockAccess access, BlockPos pos) {
        IBlockState state = getFluidState(access, pos);
        return state.getMaterial().isLiquid() || state.getBlock() instanceof IFluidBlock;
    }

    /**
     * The fluid occupying {@code pos}, which is not necessarily the block there.
     *
     * <p>With Fluidlogged API installed a position can hold both a block and a fluid; a torch inside a
     * fluidlogged fence is submerged even though the block at that position is a fence.
     */
    private static IBlockState getFluidState(IBlockAccess access, BlockPos pos) {
        return FLUIDLOGGING ? FluidloggedCompat.getFluidOrReal(access, pos) : access.getBlockState(pos);
    }
}
