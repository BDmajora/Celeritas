package com.bdmajora.impetus.umbra.pipeline.shadow;

import com.bdmajora.impetus.engine.impl.render.viewport.frustum.Frustum;

// Bounds the shadow pass to a cube of shadowDistance around the camera, position-only by design since a view-dependent set made the floodfill strobe; used for both on and reversed as a conservative superset
public final class ShadowBoxCuller implements Frustum {
    private final float maxDistance;

    public ShadowBoxCuller(float maxDistance) {
        this.maxDistance = maxDistance;
    }

    // Returns true if VISIBLE, the engine's Frustum contract and the inverse of Iris's isCulled, so the ported condition is negated; backwards culls everything the shadow map should hold
    @Override
    public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        if (maxX < -this.maxDistance || minX > this.maxDistance) {
            return false;
        }
        if (maxY < -this.maxDistance || minY > this.maxDistance) {
            return false;
        }
        return !(maxZ < -this.maxDistance) && !(minZ > this.maxDistance);
    }
}
