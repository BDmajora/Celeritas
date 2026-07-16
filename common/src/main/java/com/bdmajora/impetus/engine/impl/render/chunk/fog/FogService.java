package com.bdmajora.impetus.engine.impl.render.chunk.fog;

import com.bdmajora.impetus.engine.impl.render.chunk.shader.ChunkShaderComponent;

public interface FogService {
    float getFogEnd();
    float getFogStart();
    float getFogDensity();
    int getFogShapeIndex();
    float getFogCutoff();
    float[] getFogColor();
    ChunkShaderComponent.Factory<?> getFogMode();
}
