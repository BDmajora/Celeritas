package com.bdmajora.fulgor.lighting;

import com.bdmajora.fulgor.Fulgor;
import com.bdmajora.fulgor.api.ChunkLightingData;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.chunk.Chunk;

/**
 * The bookkeeping behind Fulgor's chunk-boundary fix.
 *
 * <p>Vanilla propagates light into a neighbouring chunk only if that chunk happens to be loaded, and
 * silently drops the update otherwise. During world generation neighbours are routinely absent, which
 * is where the lighting seams of MC-3329, MC-117067 and MC-117094 come from: the light was never
 * wrong, it was never calculated.
 *
 * <p>The fix, inherited from Phosphor, is to record what was skipped. Each chunk carries a table of
 * {@code short} section masks — one entry per (light type, direction, half of the edge, inward or
 * outward) — and {@code Chunk.onLoad} replays whatever its table and its new neighbours' tables agree
 * is outstanding. Because a chunk can be saved with work still owed, the table is serialised too.
 *
 * <h2>Why the edge is split in half</h2>
 *
 * <p>A boundary update needs both the neighbour and the <i>diagonal</i> neighbour on the side the
 * column sits on, since skylight can arrive around the corner. Splitting each 16-block edge into two
 * 8-block halves lets a half be replayed as soon as its own diagonal is available instead of waiting
 * for both.
 */
public final class NeighborLightFlags {
    public static final String NBT_KEY = "NeighborLightChecks";

    private NeighborLightFlags() {
    }

    /** Which side of a boundary an outstanding check belongs to. */
    public enum BoundaryFacing {
        IN,
        OUT;

        public BoundaryFacing getOpposite() {
            return this == IN ? OUT : IN;
        }
    }

    public static int index(EnumSkyBlock lightType, int xOffset, int zOffset,
                           EnumFacing.AxisDirection axisDirection, BoundaryFacing boundaryFacing) {
        return (lightType == EnumSkyBlock.BLOCK ? 0 : 16)
                | ((xOffset + 1) << 2)
                | ((zOffset + 1) << 1)
                | (axisDirection.getOffset() + 1)
                | boundaryFacing.ordinal();
    }

    public static int index(EnumSkyBlock lightType, EnumFacing dir,
                            EnumFacing.AxisDirection axisDirection, BoundaryFacing boundaryFacing) {
        return index(lightType, dir.getXOffset(), dir.getZOffset(), axisDirection, boundaryFacing);
    }

    /**
     * Which half of the edge the column at {@code (x, z)} falls in.
     *
     * <p>The coordinate perpendicular to the facing is the one that picks the half.
     */
    public static EnumFacing.AxisDirection axisDirection(EnumFacing dir, int x, int z) {
        return ((dir.getAxis() == EnumFacing.Axis.X ? z : x) & 15) < 8
                ? EnumFacing.AxisDirection.NEGATIVE
                : EnumFacing.AxisDirection.POSITIVE;
    }

    /** Records that {@code sectionMask}'s sections owe a check across {@code dir}, and dirties the chunk. */
    public static void flagBoundary(Chunk chunk, short sectionMask, EnumSkyBlock lightType, EnumFacing dir,
                                    EnumFacing.AxisDirection axisDirection, BoundaryFacing boundaryFacing) {
        ChunkLightingData data = (ChunkLightingData) chunk;

        data.fulgor$initNeighborLightChecks();
        data.fulgor$getNeighborLightChecks()[index(lightType, dir, axisDirection, boundaryFacing)] |= sectionMask;

        chunk.markDirty();
    }

    /**
     * Writes the table into the chunk tag, skipping it entirely when nothing is outstanding.
     *
     * <p>The all-zero case is the overwhelming majority once a world has settled, and a 32-entry list
     * of zeroes per chunk is not worth writing.
     */
    public static void write(Chunk chunk, NBTTagCompound compound) {
        short[] flags = ((ChunkLightingData) chunk).fulgor$getNeighborLightChecks();

        if (flags == null) {
            return;
        }

        boolean empty = true;
        NBTTagList list = new NBTTagList();

        for (short mask : flags) {
            list.appendTag(new NBTTagShort(mask));

            if (mask != 0) {
                empty = false;
            }
        }

        if (!empty) {
            compound.setTag(NBT_KEY, list);
        }
    }

    /** Reads the table back, ignoring one whose length does not match — a foreign or stale write. */
    public static void read(Chunk chunk, NBTTagCompound compound) {
        // Type 9 is TAG_List; the entries inside it are type 2, TAG_Short.
        if (!compound.hasKey(NBT_KEY, 9)) {
            return;
        }

        NBTTagList list = compound.getTagList(NBT_KEY, 2);

        if (list.tagCount() != Fulgor.BOUNDARY_FLAG_COUNT) {
            Fulgor.LOGGER.warn("Chunk field {} had invalid length, ignoring it (chunk coordinates: {} {})",
                    NBT_KEY, chunk.x, chunk.z);
            return;
        }

        ChunkLightingData data = (ChunkLightingData) chunk;
        data.fulgor$initNeighborLightChecks();

        short[] flags = data.fulgor$getNeighborLightChecks();

        for (int i = 0; i < Fulgor.BOUNDARY_FLAG_COUNT; i++) {
            flags[i] = ((NBTTagShort) list.get(i)).getShort();
        }
    }
}
