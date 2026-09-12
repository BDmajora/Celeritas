package com.bdmajora.impetus.engine.impl.render.chunk.map;

public interface ChunkTrackerHolder {
    // The tracker attached to a world by mixin
    static ChunkTracker get(Object world) {
        return ((ChunkTrackerHolder) world).impetus$getTracker();
    }

    ChunkTracker impetus$getTracker();
}
