package com.bdmajora.equilibrium.common.world;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;

/**
 * Direct access to a world's chunks, implemented on {@link World} by the {@code mixin.util.chunk_access}
 * mixin.
 *
 * <p>Lithium's equivalent exists because modern versions route chunk access through several layers of
 * wrapper — {@code LevelReader}, {@code ChunkSource}, {@code ChunkMap} — and every block read pays for
 * the dispatch. On 1.12.2 the chain is shorter but the map lookup at the end of it is the same, and
 * the callers that matter here read thousands of blocks in a row from a handful of chunks.
 *
 * <p>Every optimization that walks a region of blocks — explosion rays, entity collision boxes,
 * path-finding — resolves its chunks through this rather than {@code World#getChunk}, so that when
 * the option is turned off they fall back to vanilla access rather than losing their fast path
 * silently.
 */
public interface ChunkAccess {
    /**
     * The chunk at these coordinates if it is loaded, otherwise null.
     *
     * <p>Never loads or generates. Callers that walk a region use this so that reading past the edge
     * of the loaded area cannot drag a chunk in — which vanilla's {@code getChunk} would do, and
     * which is how "an explosion loaded half a dimension" bugs happen.
     */
    @Nullable
    Chunk equilibrium$getLoadedChunk(int chunkX, int chunkZ);

    /**
     * The chunk at these coordinates, consulting a one-entry cache first and otherwise behaving
     * exactly like {@code World#getChunk} — including loading or generating if that is what vanilla
     * would have done.
     */
    Chunk equilibrium$getChunkCached(int chunkX, int chunkZ);
}
