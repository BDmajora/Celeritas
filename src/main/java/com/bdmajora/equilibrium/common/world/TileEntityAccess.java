package com.bdmajora.equilibrium.common.world;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

// reads a tile entity without creating one as a side effect
// implemented on World by mixin.util.block_entity_retrieval
// World#getTileEntity asks the chunk with EnumCreateEntityType.IMMEDIATE, which means that if the
// position holds a block that should have a tile entity but does not, the chunk builds one and
// registers it
// that is right for code that is about to use the tile entity, and wrong for code that is only asking
// whether one is there - which is what the hopper does, on every transfer attempt, against a position
// it usually finds empty
public interface TileEntityAccess {
    // the tile entity at this position if one already exists, otherwise null
    // never constructs, never registers, never queues, and never loads a chunk: an unloaded position
    // answers null rather than dragging terrain in
    @Nullable
    TileEntity equilibrium$getExistingTileEntity(BlockPos pos);
}
