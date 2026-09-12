package com.bdmajora.impetus.engine.impl.render.chunk.lists;

import com.bdmajora.impetus.engine.impl.render.chunk.terrain.TerrainRenderPass;

import java.util.Iterator;

public interface ChunkRenderListIterable {
    Iterator<ChunkRenderList> iterator(boolean reverse);

    // Forward order
    default Iterator<ChunkRenderList> iterator() {
        return this.iterator(false);
    }

    // Whether any section in this list needs rendering for that pass, so the caller can skip the whole pass setup
    // — binding its program, framebuffer and state — rather than setting up for zero draws
    default boolean hasPass(TerrainRenderPass pass) {
        return true;
    }
}
