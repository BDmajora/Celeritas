package com.bdmajora.equilibrium.mixin.world.inline_block_access;

import com.bdmajora.equilibrium.common.world.ChunkAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

// getChunk is the single funnel getBlockState/getTileEntity/getLightFor etc all reach, so routing it through the cache here covers all of them without touching the callers
@Mixin(World.class)
public abstract class WorldMixin implements ChunkAccess {
    // Resolve chunks through the direct cache rather than the provider's map
    @Overwrite
    public Chunk getChunk(int chunkX, int chunkZ) {
        return this.equilibrium$getChunkCached(chunkX, chunkZ);
    }
}
