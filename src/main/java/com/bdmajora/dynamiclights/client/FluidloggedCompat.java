package com.bdmajora.dynamiclights.client;

import git.jbredwards.fluidlogged_api.api.util.FluidloggedUtils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/**
 * The one call into Fluidlogged API, kept in its own class.
 *
 * <p>Same arrangement as {@code FluidLightCompat} in Fulgor: isolating the reference means the
 * Fluidlogged classes are only ever resolved if this class is touched, and {@link FluidHandler}
 * only touches it when the mod is loaded.
 */
final class FluidloggedCompat {
    private FluidloggedCompat() {
    }

    static IBlockState getFluidOrReal(IBlockAccess access, BlockPos pos) {
        return FluidloggedUtils.getFluidOrReal(access, pos);
    }
}
