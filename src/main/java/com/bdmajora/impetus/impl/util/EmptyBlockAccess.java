package com.bdmajora.impetus.impl.util;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import org.jetbrains.annotations.Nullable;

// Stub world used when a real IBlockAccess isn't available yet (e.g. before world load); everything is air
public enum EmptyBlockAccess implements IBlockAccess {
    INSTANCE;

    private static final IBlockState AIR = Blocks.AIR.getDefaultState();

    @Override
    public @Nullable TileEntity getTileEntity(BlockPos pos) {
        return null;
    }

    // Full brightness
    @Override
    public int getCombinedLight(BlockPos blockPos, int lightValue) {
        return 0;
    }

    // Always air
    @Override
    public IBlockState getBlockState(BlockPos pos) {
        return AIR;
    }

    // Always
    @Override
    public boolean isAirBlock(BlockPos pos) {
        return true;
    }

    // Plains
    @Override
    public Biome getBiome(BlockPos pos) {
        return Biomes.PLAINS;
    }

    // None
    @Override
    public int getStrongPower(BlockPos blockPos, EnumFacing direction) {
        return 0;
    }

    // Default
    @Override
    public WorldType getWorldType() {
        return WorldType.DEFAULT;
    }

    // Never
    @Override
    public boolean isSideSolid(BlockPos blockPos, EnumFacing enumFacing, boolean bl) {
        return false;
    }
}
