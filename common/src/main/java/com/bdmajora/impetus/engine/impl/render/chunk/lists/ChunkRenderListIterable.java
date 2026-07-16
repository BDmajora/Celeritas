package com.bdmajora.impetus.engine.impl.render.chunk.lists;

import com.bdmajora.impetus.engine.impl.render.chunk.terrain.TerrainRenderPass;

import java.util.Iterator;

public interface ChunkRenderListIterable {
    Iterator<ChunkRenderList> iterator(boolean reverse);

    default Iterator<ChunkRenderList> iterator() {
        return this.iterator(false);
    }

    /**
     * {@return true if there are sections that need rendering for the given pass}
     */
    default boolean hasPass(TerrainRenderPass pass) {
        return true;
    }
}
