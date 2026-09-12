package com.bdmajora.impetus.umbra.pipeline.shadow;

import com.bdmajora.impetus.engine.impl.render.viewport.frustum.Frustum;

// Bounds the shadow pass to a cube of shadowDistance around the camera, position-only by design
// A view-dependent set made the floodfill chase a moving voxel field and strobe; a box is frame-stable
// Used for both on and reversed, since it is a conservative superset of either Iris mode
public final class ShadowBoxCuller implements Frustum {
    private final float maxDistance;

    public ShadowBoxCuller(float maxDistance) {
        this.maxDistance = maxDistance;
    }

    // Returns true if VISIBLE. That is the engine's Frustum contract and the exact inverse of Iris's isCulled, so
    // the ported condition is negated here — getting this backwards culls everything the shadow map should hold
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
