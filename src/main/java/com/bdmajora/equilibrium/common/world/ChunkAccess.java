package com.bdmajora.equilibrium.common.world;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;

// Direct chunk access on World so region walks (explosion rays, collision, path-finding) skip the per-block chunk lookup; every such optimisation resolves through this so turning it off falls back to vanilla
public interface ChunkAccess {
    // The chunk at these coordinates if loaded, else null; never loads or generates, so reading past the loaded edge cannot drag a chunk in (how "an explosion loaded half a dimension" happens)
    @Nullable
    Chunk equilibrium$getLoadedChunk(int chunkX, int chunkZ);

    // The chunk at these coordinates via a one-entry cache, otherwise exactly World#getChunk including loading or generating
    Chunk equilibrium$getChunkCached(int chunkX, int chunkZ);
}
