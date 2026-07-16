package com.bdmajora.impetus.engine.impl.render.chunk.map;

public interface ChunkTrackerHolder {
    static ChunkTracker get(Object world) {
        return ((ChunkTrackerHolder) world).impetus$getTracker();
    }

    ChunkTracker impetus$getTracker();
}
