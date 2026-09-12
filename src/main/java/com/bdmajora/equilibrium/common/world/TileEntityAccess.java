package com.bdmajora.equilibrium.common.world;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

// Reads a tile entity without creating one, implemented on World by mixin
// World.getTileEntity uses IMMEDIATE, which constructs and registers one for a block that should have it; wrong for a
// caller only asking whether one is there, which is what the hopper does on every transfer attempt
public interface TileEntityAccess {
    // the tile entity at this position if one already exists, otherwise null
    // never constructs, never registers, never queues, and never loads a chunk: an unloaded position
    // answers null rather than dragging terrain in
    @Nullable
    TileEntity equilibrium$getExistingTileEntity(BlockPos pos);
}
