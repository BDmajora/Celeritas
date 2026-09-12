package com.bdmajora.fulgor.lighting;

import git.jbredwards.fluidlogged_api.api.util.FluidState;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.chunk.Chunk;

// Folds Fluidlogged API's second block state (the fluid sharing the position) into light calc;
// real opacity/luminance is the max of the block and fluid states, so a fluidlogged sea lantern still glows.
// Kept isolated so the Fluidlogged classes only resolve when Fulgor.hasFluidloggedApi() is true.
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
