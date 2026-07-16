package com.bdmajora.impetus.engine.impl.render.chunk.multidraw;

import com.bdmajora.impetus.engine.impl.gl.device.CommandList;
import com.bdmajora.impetus.engine.impl.gl.tessellation.GlPrimitiveType;
import com.bdmajora.impetus.engine.impl.gl.tessellation.GlTessellation;
import com.bdmajora.impetus.engine.impl.model.quad.properties.ModelQuadFacing;
import com.bdmajora.impetus.engine.impl.render.chunk.region.RenderRegion;

public interface MultiDrawEmitter {
    int MAX_COMMAND_COUNT = (ModelQuadFacing.COUNT * RenderRegion.REGION_SIZE) + 1;

    void addDrawCommands(long pMeshData, int facingMask, int indexPointerMask);
    void executeBatch(CommandList commandList, GlTessellation tessellation, GlPrimitiveType primitiveType);
    boolean isEmpty();
    int getIndexBufferSize();
    void clear();
    void delete();
}
