package com.bdmajora.fulgor.lighting;

import com.bdmajora.fulgor.api.ChunkLightingData;
import com.bdmajora.fulgor.lighting.NeighborLightFlags.BoundaryFacing;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

// the chunk-level lighting operations, kept out of the mixins that call them
// everything here is about the seam between chunks: vanilla treats a missing neighbour as a reason to
// skip work, Fulgor treats it as a reason to record work, and this is where the recording and the
// later replay live
public final class LightingHooks {
    private static final EnumSkyBlock[] LIGHT_TYPES = EnumSkyBlock.values();
    private static final EnumFacing.AxisDirection[] AXIS_DIRECTIONS = EnumFacing.AxisDirection.values();

    private LightingHooks() {
    }

    // reschedules skylight for a column whose heightmap just moved
    // the interesting part is the second half: where the column passes through a section that does not
    // exist, skylight has to be re-checked in the four horizontally adjacent columns as well, because
    // light travels sideways through the empty space
    // if an adjacent column belongs to a chunk that is not loaded, the check is recorded against the
    // boundary instead of being dropped, and scheduleRelightChecksForChunkBoundaries replays it once
    // that chunk arrives
    public static void relightSkylightColumn(World world, Chunk chunk, int x, int z, int height1, int height2) {
        int yMin = Math.min(height1, height2);
        int yMax = Math.max(height1, height2) - 1;

        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();

        int xBase = (chunk.x << 4) + x;
        int zBase = (chunk.z << 4) + z;

        scheduleRelightChecksForColumn(world, EnumSkyBlock.SKY, xBase, zBase, yMin, yMax);

        // The block below an absent section can be lit through it, and it is outside the range above.
        if (sections[yMin >> 4] == Chunk.NULL_BLOCK_STORAGE && yMin > 0) {
            world.checkLightFor(EnumSkyBlock.SKY, new BlockPos(xBase, yMin - 1, zBase));
        }

        short emptySections = 0;

        for (int section = yMax >> 4; section >= yMin >> 4; section--) {
            if (sections[section] == Chunk.NULL_BLOCK_STORAGE) {
                emptySections |= (short) (1 << section);
            }
        }

        if (emptySections == 0) {
            return;
        }

        for (EnumFacing dir : EnumFacing.HORIZONTALS) {
            int xOffset = dir.getXOffset();
            int zOffset = dir.getZOffset();

            // The 16 bit is set for both -1 and 16, so this catches either edge of the chunk in one
            // test. Inside the chunk the neighbouring column is trivially available.
            boolean neighborColumnExists = (((x + xOffset) | (z + zOffset)) & 16) == 0
                    || world.getChunkProvider().getLoadedChunk(chunk.x + xOffset, chunk.z + zOffset) != null;

            if (neighborColumnExists) {
                for (int section = yMax >> 4; section >= yMin >> 4; section--) {
                    if ((emptySections & (1 << section)) != 0) {
                        scheduleRelightChecksForColumn(world, EnumSkyBlock.SKY, xBase + xOffset, zBase + zOffset,
                                section << 4, (section << 4) + 15);
                    }
                }
            } else {
                NeighborLightFlags.flagBoundary(chunk, emptySections, EnumSkyBlock.SKY, dir,
                        NeighborLightFlags.axisDirection(dir, x, z), BoundaryFacing.OUT);
            }
        }
    }

    public static void scheduleRelightChecksForArea(World world, EnumSkyBlock lightType,
                                                    int xMin, int yMin, int zMin,
                                                    int xMax, int yMax, int zMax) {
        for (int x = xMin; x <= xMax; x++) {
            for (int z = zMin; z <= zMax; z++) {
                scheduleRelightChecksForColumn(world, lightType, x, z, yMin, yMax);
            }
        }
    }

    private static void scheduleRelightChecksForColumn(World world, EnumSkyBlock lightType,
                                                       int x, int z, int yMin, int yMax) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = yMin; y <= yMax; y++) {
            world.checkLightFor(lightType, pos.setPos(x, y, z));
        }
    }

    // replays everything this chunk and its new neighbours owe each other
    // called from Chunk.onLoad, the moment a boundary that was previously impossible to cross may have
    // become crossable
    // each of the four neighbours is handled in both directions, plus the diagonal case: a check in the
    // neighbour may have been abandoned earlier precisely because *this* chunk was the missing corner
    public static void scheduleRelightChecksForChunkBoundaries(World world, Chunk chunk) {
        for (EnumFacing dir : EnumFacing.HORIZONTALS) {
            int xOffset = dir.getXOffset();
            int zOffset = dir.getZOffset();

            Chunk neighbor = world.getChunkProvider().getLoadedChunk(chunk.x + xOffset, chunk.z + zOffset);

            if (neighbor == null) {
                continue;
            }

            for (EnumSkyBlock lightType : LIGHT_TYPES) {
                for (EnumFacing.AxisDirection axisDir : AXIS_DIRECTIONS) {
                    // Fold each side's OUT flags into the other's IN flags, so from here on only the
                    // IN side has to be consulted.
                    mergeFlags(lightType, chunk, neighbor, dir, axisDir);
                    mergeFlags(lightType, neighbor, chunk, dir.getOpposite(), axisDir);

                    scheduleRelightChecksForBoundary(world, chunk, neighbor, null, lightType, xOffset, zOffset, axisDir);
                    scheduleRelightChecksForBoundary(world, neighbor, chunk, null, lightType, -xOffset, -zOffset, axisDir);

                    // The neighbour's boundary with its own diagonal, which this chunk is the corner of.
                    scheduleRelightChecksForBoundary(world, neighbor, null, chunk, lightType,
                            zOffset != 0 ? axisDir.getOffset() : 0,
                            xOffset != 0 ? axisDir.getOffset() : 0,
                            dir.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE
                                    ? EnumFacing.AxisDirection.NEGATIVE
                                    : EnumFacing.AxisDirection.POSITIVE);
                }
            }
        }
    }

    private static void mergeFlags(EnumSkyBlock lightType, Chunk inChunk, Chunk outChunk,
                                   EnumFacing dir, EnumFacing.AxisDirection axisDir) {
        ChunkLightingData outData = (ChunkLightingData) outChunk;

        if (outData.fulgor$getNeighborLightChecks() == null) {
            return;
        }

        ChunkLightingData inData = (ChunkLightingData) inChunk;
        inData.fulgor$initNeighborLightChecks();

        int inIndex = NeighborLightFlags.index(lightType, dir, axisDir, BoundaryFacing.IN);
        int outIndex = NeighborLightFlags.index(lightType, dir.getOpposite(), axisDir, BoundaryFacing.OUT);

        inData.fulgor$getNeighborLightChecks()[inIndex] |= outData.fulgor$getNeighborLightChecks()[outIndex];
        // outChunk is not marked dirty: nothing was removed from it, only copied.
    }

    // replays one half of one edge, if its flags say anything is outstanding and everything needed is
    // present
    // neighbor is the chunk across the edge and diagonal the chunk diagonally across the corner this
    // half sits on; either is looked up if null
    private static void scheduleRelightChecksForBoundary(World world, Chunk chunk, Chunk neighbor, Chunk diagonal,
                                                         EnumSkyBlock lightType, int xOffset, int zOffset,
                                                         EnumFacing.AxisDirection axisDir) {
        ChunkLightingData data = (ChunkLightingData) chunk;

        if (data.fulgor$getNeighborLightChecks() == null) {
            return;
        }

        // Only IN is consulted; mergeFlags has already folded the neighbour's OUT into it.
        int flagIndex = NeighborLightFlags.index(lightType, xOffset, zOffset, axisDir, BoundaryFacing.IN);
        short flags = data.fulgor$getNeighborLightChecks()[flagIndex];

        if (flags == 0) {
            return;
        }

        if (neighbor == null) {
            neighbor = world.getChunkProvider().getLoadedChunk(chunk.x + xOffset, chunk.z + zOffset);

            if (neighbor == null) {
                return;
            }
        }

        if (diagonal == null) {
            diagonal = world.getChunkProvider().getLoadedChunk(
                    chunk.x + (zOffset != 0 ? axisDir.getOffset() : 0),
                    chunk.z + (xOffset != 0 ? axisDir.getOffset() : 0));

            // The corner columns of this half read from the diagonal, so without it the replay would
            // produce the same wrong answer that caused the flag in the first place. Leave it flagged.
            if (diagonal == null) {
                return;
            }
        }

        int reverseIndex = NeighborLightFlags.index(lightType, -xOffset, -zOffset, axisDir, BoundaryFacing.OUT);

        data.fulgor$getNeighborLightChecks()[flagIndex] = 0;

        ChunkLightingData neighborData = (ChunkLightingData) neighbor;

        if (neighborData.fulgor$getNeighborLightChecks() != null) {
            // Cleared only now that the checks are certain to be performed.
            neighborData.fulgor$getNeighborLightChecks()[reverseIndex] = 0;
        }

        chunk.markDirty();
        neighbor.markDirty();

        // Walk to the corner of the chunk this edge belongs to...
        int xMin = chunk.x << 4;
        int zMin = chunk.z << 4;

        // ...to the far side if the direction is positive...
        if ((xOffset | zOffset) > 0) {
            xMin += 15 * xOffset;
            zMin += 15 * zOffset;
        }

        // ...and along to the second half if this is that one. (n & 1 is abs(n) for n in -1, 0, 1.)
        if (axisDir == EnumFacing.AxisDirection.POSITIVE) {
            xMin += 8 * (zOffset & 1);
            zMin += 8 * (xOffset & 1);
        }

        int xMax = xMin + 7 * (zOffset & 1);
        int zMax = zMin + 7 * (xOffset & 1);

        for (int section = 0; section < 16; section++) {
            if ((flags & (1 << section)) != 0) {
                scheduleRelightChecksForArea(world, lightType, xMin, section << 4, zMin,
                        xMax, (section << 4) + 15, zMax);
            }
        }
    }

    // seeds a chunk's block light by scheduling every light-emitting block in it
    // vanilla does this from Chunk.checkLight as an immediate relight of all 65536 columns; here it is
    // 65536 cheap luminance reads and a handful of scheduled updates, which the engine then resolves in
    // one batch
    // deliberately does nothing unless the full 3x3 neighbourhood is loaded: light seeded against
    // missing neighbours is exactly the wrong light, so the chunk stays uninitialised and a later
    // attempt can do it properly
    public static void initChunkLighting(World world, Chunk chunk) {
        int xBase = chunk.x << 4;
        int zBase = chunk.z << 4;

        BlockPos.PooledMutableBlockPos pos = BlockPos.PooledMutableBlockPos.retain(xBase, 0, zBase);

        try {
            if (!world.isAreaLoaded(pos.add(-16, 0, -16), pos.add(31, 255, 31), false)) {
                return;
            }

            ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();

            for (int i = 0; i < sections.length; i++) {
                ExtendedBlockStorage section = sections[i];

                if (section == Chunk.NULL_BLOCK_STORAGE) {
                    continue;
                }

                int yBase = i << 4;

                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            pos.setPos(xBase + x, yBase + y, zBase + z);

                            if (LightUtil.getLightValue(section.getData().get(x, y, z), world, pos, chunk) > 0) {
                                world.checkLightFor(EnumSkyBlock.BLOCK, pos);
                            }
                        }
                    }
                }
            }

            if (world.provider.hasSkyLight()) {
                ((ChunkLightingData) chunk).fulgor$setSkylightUpdated();
            }

            ((ChunkLightingData) chunk).fulgor$setLightInitialized(true);
        } finally {
            pos.release();
        }
    }

    // the replacement for Chunk.checkLight's per-column relight
    // a chunk is only marked light-populated once it and all eight neighbours have been seeded, which
    // is what stops the light at a chunk border from being finalised against a neighbour that has not
    // been lit yet - the cause of the border seams during world generation
    public static void checkChunkLighting(World world, Chunk chunk) {
        if (!((ChunkLightingData) chunk).fulgor$isLightInitialized()) {
            initChunkLighting(world, chunk);
        }

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }

                Chunk neighbor = world.getChunkProvider().getLoadedChunk(chunk.x + x, chunk.z + z);

                if (neighbor == null || !((ChunkLightingData) neighbor).fulgor$isLightInitialized()) {
                    return;
                }
            }
        }

        chunk.setLightPopulated(true);
    }

    // fills a newly created section's skylight from the heightmap
    // stands in for Chunk.generateSkylightMap, which rebuilds the entire column stack of the chunk
    // only the new section can possibly need filling, and only the columns whose terrain height is at
    // or below it - everything else either already has its value or is still in shadow
    public static void initSkylightForSection(World world, Chunk chunk, ExtendedBlockStorage section) {
        if (!world.provider.hasSkyLight()) {
            return;
        }

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (chunk.getHeightValue(x, z) > section.getYLocation()) {
                    continue;
                }

                for (int y = 0; y < 16; y++) {
                    section.setSkyLight(x, y, z, EnumSkyBlock.SKY.defaultLightValue);
                }
            }
        }
    }
}
