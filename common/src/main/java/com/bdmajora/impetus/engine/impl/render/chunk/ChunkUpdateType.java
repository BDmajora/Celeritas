package com.bdmajora.impetus.engine.impl.render.chunk;

// the type of chunk update task
public enum ChunkUpdateType {
    // chunk is being built for the first time
    // the maximum queue size is somewhat arbitrarily chosen: large enough to keep a reasonably sized
    // worker pool saturated with initial builds during world load, while bounding the per-frame
    // snapshot burst on the render thread
    INITIAL_BUILD,
    // chunk geometry is being sorted because the camera position changed
    SORT,
    // like SORT, but blocks the main thread if the camera is near enough to guarantee the sort results
    // are reflected quickly
    IMPORTANT_SORT,
    // chunk data has changed and remeshing is required
    REBUILD,
    // like REBUILD, but blocks the main thread if the camera is near enough to guarantee the rebuild is
    // seen quickly
    IMPORTANT_REBUILD;

    // Whether a new request outranks the one already queued
    @Deprecated
    public static boolean canPromote(ChunkUpdateType prev, ChunkUpdateType next) {
        return prev == null || (prev == REBUILD && next == IMPORTANT_REBUILD);
    }

    // borrowed from PR #2016
    public static ChunkUpdateType getPromotionUpdateType(ChunkUpdateType prev, ChunkUpdateType next) {
        if (prev == next)
            return null; // No point submitting the same update twice

        if (prev == null || prev == SORT) {
            return next;
        }
        if (next == IMPORTANT_REBUILD
                || (prev == IMPORTANT_SORT && next == REBUILD)
                || (prev == REBUILD && next == IMPORTANT_SORT)) {
            return IMPORTANT_REBUILD;
        }
        return null;
    }

    // true if the task is "important" and should block the main thread when the camera is near enough
    public boolean isImportant() {
        return this == IMPORTANT_REBUILD || this == IMPORTANT_SORT;
    }

    // true if the task only sorts, rather than performing a full chunk rebuild
    public boolean isSort() {
        return this == SORT || this == IMPORTANT_SORT;
    }
}
