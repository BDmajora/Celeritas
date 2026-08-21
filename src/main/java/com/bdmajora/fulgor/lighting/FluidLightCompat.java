package com.bdmajora.fulgor.lighting;

import git.jbredwards.fluidlogged_api.api.util.FluidState;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.chunk.Chunk;

/**
 * Folds Fluidlogged API's second block state at a position into the light calculation.
 *
 * <p>A fluidlogged position holds two states — the block and the fluid occupying the same space — and
 * the position's real opacity and luminance are the larger of the two. Water inside a fence darkens
 * like water; a fluidlogged sea lantern still glows.
 *
 * <p>Isolated in its own class so the Fluidlogged classes are only ever resolved on a call, and
 * {@link com.bdmajora.fulgor.Fulgor#hasFluidloggedApi()} guards every call site.
 */
final class FluidLightCompat {
    private FluidLightCompat() {
    }

    static int getLightOpacity(IBlockState state, IBlockAccess world, BlockPos pos, Chunk chunk) {
        FluidState fluid = FluidState.getFromProvider(chunk, pos);

        if (fluid.isEmpty()) {
            return state.getLightOpacity(world, pos);
        }

        return Math.max(fluid.getState().getLightOpacity(world, pos), state.getLightOpacity(world, pos));
    }

    static int getLightValue(int blockLightValue, IBlockAccess world, BlockPos pos, Chunk chunk) {
        FluidState fluid = FluidState.getFromProvider(chunk, pos);

        if (fluid.isEmpty()) {
            return blockLightValue;
        }

        return Math.max(fluid.getState().getLightValue(world, pos), blockLightValue);
    }
}
