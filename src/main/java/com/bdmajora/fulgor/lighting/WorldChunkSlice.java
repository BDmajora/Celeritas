package com.bdmajora.fulgor.lighting;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

// 5x5 chunk snapshot around one column for recheckGaps: 25 lookups up front instead of 1024 through the provider
// Entries may be null; use isLoaded rather than piecemeal null checks, since a partial answer produces skylight seams
public final class WorldChunkSlice {
    private static final int DIAMETER = 5;
    private static final int RADIUS = DIAMETER / 2;

    private final Chunk[] chunks = new Chunk[DIAMETER * DIAMETER];

    // Chunk coords of the slice's corner, for rebasing world coords onto the array
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

    // The chunk containing the given block coordinates, or null if it was not loaded
    public Chunk getChunkFromWorldCoords(int x, int z) {
        return getChunk((x >> 4) - this.originX, (z >> 4) - this.originZ);
    }

    // Whether every chunk within radius blocks of the given position is present
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

    // Slice-relative lookup; null for an unloaded neighbour
    private Chunk getChunk(int x, int z) {
        if (x < 0 || x >= DIAMETER || z < 0 || z >= DIAMETER) {
            return null;
        }

        return this.chunks[(x * DIAMETER) + z];
    }
}
