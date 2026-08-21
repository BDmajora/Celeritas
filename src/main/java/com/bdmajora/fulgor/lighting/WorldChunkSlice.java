package com.bdmajora.fulgor.lighting;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

/**
 * A 5×5 snapshot of the chunks around one column.
 *
 * <p>Exists for {@code Chunk.recheckGaps}, which walks all 256 columns of a chunk and asks about the
 * height of each column's four neighbours. Done through the chunk provider that is 1024 lookups per
 * call, all of them into the same twenty-five chunks; taken once up front it is twenty-five.
 *
 * <p>Entries may be null. A caller that needs the whole neighbourhood present should ask
 * {@link #isLoaded} rather than null-checking as it goes, because a partial answer from a partially
 * loaded neighbourhood is what produces the skylight seams in the first place.
 */
public final class WorldChunkSlice {
    private static final int DIAMETER = 5;
    private static final int RADIUS = DIAMETER / 2;

    private final Chunk[] chunks = new Chunk[DIAMETER * DIAMETER];

    /** Chunk coordinates of the slice's corner, so world coordinates can be rebased onto the array. */
    private final int originX;
    private final int originZ;

    public WorldChunkSlice(IChunkProvider chunkProvider, int x, int z) {
        for (int xDiff = -RADIUS; xDiff <= RADIUS; xDiff++) {
            for (int zDiff = -RADIUS; zDiff <= RADIUS; zDiff++) {
                this.chunks[((xDiff + RADIUS) * DIAMETER) + (zDiff + RADIUS)] =
                        chunkProvider.getLoadedChunk(x + xDiff, z + zDiff);
            }
        }

        this.originX = x - RADIUS;
        this.originZ = z - RADIUS;
    }

    /** The chunk containing the given block coordinates, or null if it was not loaded. */
    public Chunk getChunkFromWorldCoords(int x, int z) {
        return getChunk((x >> 4) - this.originX, (z >> 4) - this.originZ);
    }

    /** Whether every chunk within {@code radius} blocks of the given position is present. */
    public boolean isLoaded(int x, int z, int radius) {
        int xStart = ((x - radius) >> 4) - this.originX;
        int zStart = ((z - radius) >> 4) - this.originZ;
        int xEnd = ((x + radius) >> 4) - this.originX;
        int zEnd = ((z + radius) >> 4) - this.originZ;

        for (int currentX = xStart; currentX <= xEnd; currentX++) {
            for (int currentZ = zStart; currentZ <= zEnd; currentZ++) {
                if (getChunk(currentX, currentZ) == null) {
                    return false;
                }
            }
        }

        return true;
    }

    private Chunk getChunk(int x, int z) {
        if (x < 0 || x >= DIAMETER || z < 0 || z >= DIAMETER) {
            return null;
        }

        return this.chunks[(x * DIAMETER) + z];
    }
}
