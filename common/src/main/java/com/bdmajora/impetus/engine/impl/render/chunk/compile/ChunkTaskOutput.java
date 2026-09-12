package com.bdmajora.impetus.engine.impl.render.chunk.compile;

import com.bdmajora.impetus.engine.impl.render.chunk.RenderSection;

public abstract class ChunkTaskOutput {
    public final RenderSection render;
    public final int buildTime;

    protected ChunkTaskOutput(RenderSection render, int buildTime) {
        this.render = render;
        this.buildTime = buildTime;
    }

    // Subclasses free whatever they own
    public void delete() {

    }
}
