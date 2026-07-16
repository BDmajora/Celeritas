package com.bdmajora.impetus.engine.impl.render.chunk.shader;

import com.bdmajora.impetus.engine.impl.gl.tessellation.GlPrimitiveType;
import com.bdmajora.impetus.engine.impl.render.chunk.terrain.TerrainRenderPass;
import org.joml.Matrix4fc;

public interface ChunkShaderInterface {
    void setupState(TerrainRenderPass pass);
    default void restoreState() {}
    GlPrimitiveType getPrimitiveType();
    void setProjectionMatrix(Matrix4fc matrix);
    void setModelViewMatrix(Matrix4fc matrix);
    void setRegionOffset(float x, float y, float z);
    void setTextureSlot(ChunkShaderTextureSlot slot, int val);

    default void setSectionAges(long timestamp, long[] loadTimes) {

    }
}
