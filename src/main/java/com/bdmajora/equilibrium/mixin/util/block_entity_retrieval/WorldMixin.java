package com.bdmajora.equilibrium.mixin.util.block_entity_retrieval;

import com.bdmajora.equilibrium.common.world.ChunkAccess;
import com.bdmajora.equilibrium.common.world.TileEntityAccess;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;

import javax.annotation.Nullable;

// Gives World a non-creating tile entity lookup, purely additive so World#getTileEntity keeps creating for callers that depend on it; EnumCreateEntityType.CHECK returns what is actually there, unlike IMMEDIATE and QUEUED
@Mixin(World.class)
public abstract class WorldMixin implements TileEntityAccess {
    @Nullable
    @Override
    public TileEntity equilibrium$getExistingTileEntity(BlockPos pos) {
        World world = (World) (Object) this;

        // Outside the build height a chunk has nothing to answer with; vanilla's own guard here is why getTileEntity does not index out of bounds
        if (world.isOutsideBuildHeight(pos)) {
            return null;
        }

        Chunk chunk = this instanceof ChunkAccess
                ? ((ChunkAccess) this).equilibrium$getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4)
                : world.getChunk(pos);

        if (chunk == null) {
            return null;
        }

        TileEntity tileEntity = chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);

        // The chunk hands back invalidated tile entities until pruned; the hopper would otherwise cache one and keep transferring into a block that no longer exists
        return tileEntity != null && !tileEntity.isInvalid() ? tileEntity : null;
    }
}
