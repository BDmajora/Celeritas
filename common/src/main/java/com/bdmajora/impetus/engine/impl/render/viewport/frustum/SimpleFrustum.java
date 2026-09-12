package com.bdmajora.impetus.engine.impl.render.viewport.frustum;

import org.joml.FrustumIntersection;

public final class SimpleFrustum implements Frustum {
    private final FrustumIntersection frustum;

    public SimpleFrustum(FrustumIntersection frustumIntersection) {
        this.frustum = frustumIntersection;
    }

    // Delegates to JOML's frustum intersection
    @Override
    public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return this.frustum.testAab(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
