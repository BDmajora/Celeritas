package com.bdmajora.impetus.impl.world.cloned;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeColorHelper;
import com.bdmajora.impetus.impl.compat.fluidlogged.FluidloggedBlockAccess;

/**
 * Contains extensions to the vanilla {@link IBlockAccess}.
 */
public interface ImpetusBlockAccess extends IBlockAccess, FluidloggedBlockAccess {
    int getBlockTint(BlockPos pos, BiomeColorHelper.ColorResolver resolver);
}
