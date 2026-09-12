package com.bdmajora.equilibrium.common.world;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

// Reads a tile entity without creating one (on World by mixin); World.getTileEntity uses IMMEDIATE and constructs one, wrong for a hopper only asking whether one exists
public interface TileEntityAccess {
    // The tile entity at this position if it already exists, else null; never constructs, registers, queues or loads a chunk
    @Nullable
    TileEntity equilibrium$getExistingTileEntity(BlockPos pos);
}
