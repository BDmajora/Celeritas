package com.bdmajora.fulgor.api;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;

/**
 * The lighting state Fulgor adds to every {@code Chunk}.
 *
 * <p>Three separate concerns, kept on one interface because they share a lifetime — all three are
 * born with the chunk, saved with it and read back with it.
 */
public interface ChunkLightingData {
    /**
     * Light updates this chunk owes a neighbour that was not loaded when they were scheduled.
     *
     * <p>Vanilla's answer to "the neighbour is missing" is to drop the update, which is the root of
     * the world-generation light seams (MC-3329 and friends). Fulgor records the section mask instead
     * and replays it from {@code onLoad}, so the table has to survive a save/load round trip.
     *
     * <p>Null until something needs flagging; {@link #fulgor$initNeighborLightChecks()} allocates it.
     * Indexed by {@code NeighborLightFlags.index(...)}, {@code BOUNDARY_FLAG_COUNT} entries wide.
     */
    short[] fulgor$getNeighborLightChecks();

    void fulgor$setNeighborLightChecks(short[] data);

    /** Allocates the table if it does not exist yet. */
    void fulgor$initNeighborLightChecks();

    /**
     * Whether this chunk's initial block light has been seeded.
     *
     * <p>Stored as {@code LightPopulated} in the chunk NBT, the same tag vanilla uses, so a world that
     * has been played without Fulgor does not relight from scratch.
     */
    boolean fulgor$isLightInitialized();

    void fulgor$setLightInitialized(boolean lightInitialized);

    /** Exposes {@code Chunk.setSkylightUpdated()}, which is protected. */
    void fulgor$setSkylightUpdated();

    /**
     * Reads stored light without flushing the queues first.
     *
     * <p>This is the difference that makes deferral possible: {@code getLightFor} processes pending
     * updates and then calls this, while the engine — which is mid-update and must not recurse —
     * calls this directly.
     */
    int fulgor$getCachedLightFor(EnumSkyBlock lightType, BlockPos pos);
}
