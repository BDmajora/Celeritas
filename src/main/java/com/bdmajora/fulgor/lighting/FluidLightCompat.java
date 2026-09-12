package com.bdmajora.fulgor.lighting;

import git.jbredwards.fluidlogged_api.api.util.FluidState;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.chunk.Chunk;

// Folds Fluidlogged API's second block state into light calc (opacity/luminance is the max of both, so a fluidlogged sea lantern still glows); isolated so its classes only resolve when Fulgor.hasFluidloggedApi()
final class FluidLightCompat {
    private FluidLightCompat() {
    }

    // Max of the block and fluid opacity, so a fluidlogged block does not lose the fluid's contribution
    static int getLightOpacity(IBlockState state, IBlockAccess world, BlockPos pos, Chunk chunk) {
        FluidState fluid = FluidState.getFromProvider(chunk, pos);

        if (fluid.isEmpty()) {
            return state.getLightOpacity(world, pos);
        }

        return Math.max(fluid.getState().getLightOpacity(world, pos), state.getLightOpacity(world, pos));
    }

    // Max of the block and fluid luminance, so a fluidlogged sea lantern still glows
    static int getLightValue(int blockLightValue, IBlockAccess world, BlockPos pos, Chunk chunk) {
        FluidState fluid = FluidState.getFromProvider(chunk, pos);

        if (fluid.isEmpty()) {
            return blockLightValue;
        }

        return Math.max(fluid.getState().getLightValue(world, pos), blockLightValue);
    }
}
