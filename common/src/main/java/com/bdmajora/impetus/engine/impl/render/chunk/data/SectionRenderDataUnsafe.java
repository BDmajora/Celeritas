package com.bdmajora.impetus.engine.impl.render.chunk.data;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// Section draw data in raw native memory (the render path is memory-bound and HotSpot scatters objects): u64 slice_mask, then per facing u32 vertex_offset, u32 element_count, u32 index_offset
public class SectionRenderDataUnsafe {
    private static final long OFFSET_SLICE_MASK = 0;
    private static final long OFFSET_SLICE_RANGES = 8;

    private static final long DATA_PER_FACING_SIZE = 12;
    private static final long NUM_FACINGS = 7; // 6 directions + UNASSIGNED

    private static final long STRIDE = 8 + (DATA_PER_FACING_SIZE * NUM_FACINGS);

    // Native block for this many sections
    public static long allocateHeap(int count) {
        return LWJGL.nmemCalloc(count, STRIDE);
    }

    // Frees the block
    public static void freeHeap(long pointer) {
        LWJGL.nmemFree(pointer);
    }

    // Zeros one section's data
    public static void clear(long pointer) {
        LWJGL.memSet(pointer, 0x0, STRIDE);
    }

    // Pointer to one section within the block
    public static long heapPointer(long ptr, int index) {
        return ptr + (index * STRIDE);
    }

    // Which facings have geometry
    public static void setSliceMask(long ptr, int value) {
        LWJGL.memPutInt(ptr + OFFSET_SLICE_MASK, value);
    }

    // Which facings have geometry
    public static int getSliceMask(long ptr) {
        return LWJGL.memGetInt(ptr + OFFSET_SLICE_MASK);
    }

    // First vertex of a facing
    public static void setVertexOffset(long ptr, int facing, int value) {
        LWJGL.memPutInt(ptr + OFFSET_SLICE_RANGES + (facing * DATA_PER_FACING_SIZE) + 0L, value);
    }

    // First vertex of a facing
    public static int getVertexOffset(long ptr, int facing) {
        return LWJGL.memGetInt(ptr + OFFSET_SLICE_RANGES + (facing * DATA_PER_FACING_SIZE) + 0L);
    }

    // Index count of a facing
    public static void setElementCount(long ptr, int facing, int value) {
        LWJGL.memPutInt(ptr + OFFSET_SLICE_RANGES + (facing * DATA_PER_FACING_SIZE) + 4L, value);
    }

    // Index count of a facing
    public static int getElementCount(long ptr, int facing) {
        return LWJGL.memGetInt(ptr + OFFSET_SLICE_RANGES + (facing * DATA_PER_FACING_SIZE) + 4L);
    }

    // First index of a facing
    public static void setIndexOffset(long ptr, int facing, int value) {
        LWJGL.memPutInt(ptr + OFFSET_SLICE_RANGES + (facing * DATA_PER_FACING_SIZE) + 8L, value);
    }

    // First index of a facing
    public static int getIndexOffset(long ptr, int facing) {
        return LWJGL.memGetInt(ptr + OFFSET_SLICE_RANGES + (facing * DATA_PER_FACING_SIZE) + 8L);
    }
}