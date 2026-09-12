package com.bdmajora.impetus.engine.impl.gl.buffer;

import com.bdmajora.impetus.engine.impl.gl.attribute.GlVertexFormat;
import com.bdmajora.impetus.engine.impl.common.util.NativeBuffer;

// Pairs the raw vertex/index buffers with the format needed to interpret them
public record IndexedVertexData(GlVertexFormat vertexFormat,
                                NativeBuffer vertexBuffer,
                                NativeBuffer indexBuffer) {
    // Frees both native buffers
    public void delete() {
        this.vertexBuffer.free();
        this.indexBuffer.free();
    }
}
