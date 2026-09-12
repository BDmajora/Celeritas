package com.bdmajora.impetus.engine.impl.gl.device;

import com.bdmajora.impetus.engine.impl.gl.buffer.GlBuffer;
import com.bdmajora.impetus.engine.impl.gl.tessellation.GlIndexType;
import com.bdmajora.impetus.engine.impl.gl.tessellation.GlPrimitiveType;

public interface DrawCommandList extends AutoCloseable {
    void multiDrawElementsBaseVertex(MultiDrawBatch batch, GlPrimitiveType primitiveType, GlIndexType indexType);

    void multiDrawElementsIndirect(GlBuffer indirectBuffer, int count, GlPrimitiveType primitiveType, GlIndexType indexType);

    void endTessellating();

    void flush();

    // Ends tessellation, so try-with-resources always unbinds
    @Override
    default void close() {
        this.flush();
    }
}
