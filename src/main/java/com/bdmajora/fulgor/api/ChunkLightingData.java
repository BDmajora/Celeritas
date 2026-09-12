package com.bdmajora.fulgor.api;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;

// Lighting state Fulgor adds to every Chunk; all three fields share the chunk's lifetime (save/load together)
public interface ChunkLightingData {
    // Vanilla drops updates to unloaded neighbours (MC-3329); Fulgor stores the section mask and replays it on load. Null until fulgor$initNeighborLightChecks(), indexed by NeighborLightFlags.index, BOUNDARY_FLAG_COUNT wide
    short[] fulgor$getNeighborLightChecks();

    void fulgor$setNeighborLightChecks(short[] data);

    // Allocates the table if it does not exist yet
    void fulgor$initNeighborLightChecks();

    // Stored as LightPopulated in chunk NBT (vanilla's tag), so pre-Fulgor worlds don't relight from scratch
    boolean fulgor$isLightInitialized();

    void fulgor$setLightInitialized(boolean lightInitialized);

    // Exposes Chunk.setSkylightUpdated(), which is protected
    void fulgor$setSkylightUpdated();

    // Reads stored light without flushing queues; getLightFor flushes then calls this, the engine (mid-update, must not recurse) calls it directly
    int fulgor$getCachedLightFor(EnumSkyBlock lightType, BlockPos pos);
}
