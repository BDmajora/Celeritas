package com.bdmajora.equilibrium.common.world;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;

// direct access to a world's chunks, implemented on World by the mixin.util.chunk_access mixin
// Lithium's equivalent exists because modern versions route chunk access through several layers of
// wrapper - LevelReader, ChunkSource, ChunkMap - and every block read pays for the dispatch
// on 1.12.2 the chain is shorter but the map lookup at the end of it is the same, and the callers that
// matter here read thousands of blocks in a row from a handful of chunks
// every optimization that walks a region of blocks - explosion rays, entity collision boxes,
// path-finding - resolves its chunks through this rather than World#getChunk, so that when the option
// is turned off they fall back to vanilla access rather than losing their fast path silently
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
