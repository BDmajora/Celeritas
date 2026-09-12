package com.bdmajora.dynamiclights.client;

import git.jbredwards.fluidlogged_api.api.util.FluidloggedUtils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

// The one call into Fluidlogged API, isolated in its own class so its classes are only resolved when FluidHandler actually touches this (i.e. the mod is loaded)
final class FluidloggedCompat {
    private FluidloggedCompat() {
    }

    // The fluid state sharing the position if there is one, else the real block state
    static IBlockState getFluidOrReal(IBlockAccess access, BlockPos pos) {
        return FluidloggedUtils.getFluidOrReal(access, pos);
    }
}
