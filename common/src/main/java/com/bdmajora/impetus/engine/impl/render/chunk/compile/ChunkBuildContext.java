package com.bdmajora.impetus.engine.impl.render.chunk.compile;

import com.bdmajora.impetus.engine.impl.render.chunk.RenderPassConfiguration;

public class ChunkBuildContext {
    public final ChunkBuildBuffers buffers;

    public ChunkBuildContext(RenderPassConfiguration renderPassConfiguration) {
        this.buffers = new ChunkBuildBuffers(renderPassConfiguration);
    }

    // Resets per-section state so the context can be reused for the next
    public void cleanup() {
        this.buffers.destroy();
    }
}
