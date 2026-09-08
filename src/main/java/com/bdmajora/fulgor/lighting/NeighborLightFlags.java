package com.bdmajora.fulgor.lighting;

import com.bdmajora.fulgor.Fulgor;
import com.bdmajora.fulgor.api.ChunkLightingData;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.chunk.Chunk;

// Bookkeeping for Fulgor's chunk-boundary fix (inherited from Phosphor): vanilla drops light
// propagation into a chunk that isn't loaded yet (MC-3329, MC-117067, MC-117094 come from this).
// Each chunk keeps a table of short section masks, one per (light type, direction, edge half,
// in/out); Chunk.onLoad replays whatever's outstanding once both sides agree, and the table is
// serialised since a chunk can be saved with work still owed.
// Edge is split into 8-block halves because skylight can arrive around the diagonal neighbour,
// letting a half replay as soon as its own diagonal loads instead of waiting on both.
public final class NeighborLightFlags {
    public static final String NBT_KEY = "NeighborLightChecks";

    private NeighborLightFlags() {
    }

    // Which side of a boundary an outstanding check belongs to
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

    // Which half of the edge (x, z) falls in; picked by the coordinate perpendicular to the facing
    public static EnumFacing.AxisDirection axisDirection(EnumFacing dir, int x, int z) {
        return ((dir.getAxis() == EnumFacing.Axis.X ? z : x) & 15) < 8
                ? EnumFacing.AxisDirection.NEGATIVE
                : EnumFacing.AxisDirection.POSITIVE;
    }

    // Records that sectionMask's sections owe a check across dir, and dirties the chunk
    public static void flagBoundary(Chunk chunk, short sectionMask, EnumSkyBlock lightType, EnumFacing dir,
                                    EnumFacing.AxisDirection axisDirection, BoundaryFacing boundaryFacing) {
        ChunkLightingData data = (ChunkLightingData) chunk;

        data.fulgor$initNeighborLightChecks();
        data.fulgor$getNeighborLightChecks()[index(lightType, dir, axisDirection, boundaryFacing)] |= sectionMask;

        chunk.markDirty();
    }

    // Skips writing entirely when the table is all-zero (the common case once a world settles),
    // to avoid a 32-entry list of zeroes per chunk
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

    // Ignores the table if its length doesn't match — a foreign or stale write
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
