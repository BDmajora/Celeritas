package com.bdmajora.dynamiclights.client;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fml.common.Loader;

// Whether a position is inside a fluid, which drives "extinguish when underwater"
// Checks both the block's material and Forge's IFluidBlock, so modded fluids count too, not just water and lava
public final class FluidHandler {
    private static final boolean FLUIDLOGGING = Loader.isModLoaded("fluidlogged_api");

    private FluidHandler() {
    }

    // Whether the entity's eyes are inside a fluid at the current render partial tick
    public static boolean isFluid(Entity entity) {
        float partialTicks = Minecraft.getMinecraft().getRenderPartialTicks();
        return isFluid(entity.world, new BlockPos(entity.getPositionEyes(partialTicks)));
    }

    // Whether the position holds a liquid, checking Fluidlogged's second state when that mod is present
    public static boolean isFluid(IBlockAccess access, BlockPos pos) {
        IBlockState state = getFluidState(access, pos);
        return state.getMaterial().isLiquid() || state.getBlock() instanceof IFluidBlock;
    }

    // Fluid occupying pos, not necessarily the block there; with Fluidlogged API a position can hold both a block and a fluid (e.g. a fluidlogged fence)
    private static IBlockState getFluidState(IBlockAccess access, BlockPos pos) {
        return FLUIDLOGGING ? FluidloggedCompat.getFluidOrReal(access, pos) : access.getBlockState(pos);
    }
}
