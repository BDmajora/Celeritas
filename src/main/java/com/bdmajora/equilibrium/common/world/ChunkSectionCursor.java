package com.bdmajora.equilibrium.common.world;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * A cursor for reading many blocks from one region of the world.
 *
 * <p>Vanilla's {@code World#getBlockState} resolves a chunk and then a section for every single
 * position, which is the right shape for one-off reads and the wrong shape for the callers that
 * matter here: an explosion ray walks sixteen blocks in a line, a collision test walks a box, a
 * path-finder walks a neighbourhood. All of them stay inside one chunk section for long runs.
 *
 * <p>The cursor holds the chunk and the section it last touched and only re-resolves when the run
 * crosses a boundary. A cursor is a short-lived local — one per explosion, one per movement step —
 * and is never shared between threads, which is what lets it hold mutable state without any of the
 * validation {@link ChunkAccess} needs.
 *
 * <p>Two cases fall back to {@link World#getBlockState}: the debug world, whose blocks are computed
 * rather than stored, and any world whose {@link ChunkAccess} is missing because
 * {@code mixin.util.chunk_access} is off. Both are resolved once, in the constructor, rather than
 * tested per read.
 *
 * <p>Whether reading an unloaded chunk loads it is the caller's decision, made at construction. It
 * has to be: vanilla is not consistent about it. {@code World#getBlockState} loads whatever it
 * touches, so an explosion at the edge of the loaded area really does generate terrain, and a cursor
 * that quietly declined to would let blasts punch further than they should. But
 * {@code World#getCollisionBoxes} checks {@code isBlockLoaded} first and treats the outside as empty.
 * A cursor that picked one behaviour for both would break one of them.
 */
public final class ChunkSectionCursor {
    private static final IBlockState AIR = Blocks.AIR.getDefaultState();

    private final World world;

    /** Null when the world does not implement {@link ChunkAccess}, i.e. the option is off. */
    private final ChunkAccess access;

    /** True in the debug world, where block states are generated on read and not held in sections. */
    private final boolean synthetic;

    /** Whether a read outside the loaded area should load the chunk, as {@code getBlockState} does. */
    private final boolean loadChunks;

    private Chunk chunk;
    private int chunkX = Integer.MIN_VALUE;
    private int chunkZ = Integer.MIN_VALUE;

    private ExtendedBlockStorage section;
    private int sectionY = Integer.MIN_VALUE;

    /**
     * @param loadChunks true to match {@code World#getBlockState}, false to treat unloaded chunks as
     *                   air the way {@code World#getCollisionBoxes} does
     */
    public ChunkSectionCursor(World world, boolean loadChunks) {
        this.world = world;
        this.access = world instanceof ChunkAccess ? (ChunkAccess) world : null;
        this.synthetic = world.getWorldType() == WorldType.DEBUG_ALL_BLOCK_STATES;
        this.loadChunks = loadChunks;
    }

    /** The state at this position, or air if it is outside the world or in a chunk we cannot see. */
    public IBlockState getBlockState(int x, int y, int z) {
        if (y < 0 || y > 255) {
            return AIR;
        }

        if (this.synthetic || this.access == null) {
            return this.world.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z));
        }

        int newChunkX = x >> 4;
        int newChunkZ = z >> 4;
        int newSectionY = y >> 4;

        if (newChunkX != this.chunkX || newChunkZ != this.chunkZ) {
            this.chunkX = newChunkX;
            this.chunkZ = newChunkZ;
            this.chunk = this.loadChunks
                    ? this.access.equilibrium$getChunkCached(newChunkX, newChunkZ)
                    : this.access.equilibrium$getLoadedChunk(newChunkX, newChunkZ);

            // Force the section to be re-resolved; the y index alone no longer identifies it.
            this.sectionY = Integer.MIN_VALUE;
        }

        if (this.chunk == null) {
            return AIR;
        }

        if (newSectionY != this.sectionY) {
            this.sectionY = newSectionY;

            ExtendedBlockStorage[] sections = this.chunk.getBlockStorageArray();
            ExtendedBlockStorage candidate = newSectionY < sections.length ? sections[newSectionY] : null;

            this.section = candidate == Chunk.NULL_BLOCK_STORAGE ? null : candidate;
        }

        return this.section == null ? AIR : this.section.get(x & 15, y & 15, z & 15);
    }
}
