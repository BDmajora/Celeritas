package com.bdmajora.impetus.engine.impl.model.light;

import com.bdmajora.impetus.engine.impl.model.quad.properties.ModelQuadFacing;

public interface DiffuseProvider {
    DiffuseProvider NONE = (normalX, normalY, normalZ, shade) -> 1.0f;

    float getDiffuse(float normalX, float normalY, float normalZ, boolean shade);

    default float getDiffuse(ModelQuadFacing lightFace, boolean shade) {
        return getDiffuse(lightFace.getStepX(), lightFace.getStepY(), lightFace.getStepZ(), shade);
    }
}
