package com.bdmajora.equilibrium.common.world;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;

// Direct chunk access on World, so region walks (explosion rays, collision, path-finding) skip the per-block
// chunk lookup; every such optimisation resolves through this so turning the option off falls back to vanilla
public interface ChunkAccess {
    // the chunk at these coordinates if it is loaded, otherwise null
    // never loads or generates: callers that walk a region use this so that reading past the edge of
    // the loaded area cannot drag a chunk in, which vanilla's getChunk would do and which is how "an
    // explosion loaded half a dimension" bugs happen
    @Nullable
    Chunk equilibrium$getLoadedChunk(int chunkX, int chunkZ);

    // the chunk at these coordinates, consulting a one-entry cache first and otherwise behaving
    // exactly like World#getChunk - including loading or generating if that is what vanilla would
    // have done
    Chunk equilibrium$getChunkCached(int chunkX, int chunkZ);
}
