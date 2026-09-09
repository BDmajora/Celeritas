package com.bdmajora.impetus.umbra.pipeline.shadow;

import com.bdmajora.impetus.engine.impl.render.viewport.frustum.Frustum;

// Port of Iris's shadows.frustum.BoxCuller, in its isCulledSodium form: bounds the shadow pass to a cube of
// shadowDistance around the camera instead of walking every loaded section
// The engine hands the frustum CAMERA-RELATIVE coordinates, so the test is a plain comparison against
// +/-maxDistance with no per-frame re-centring
// Position-only by design, and that is the whole point. Iris also ships AdvancedShadowCullingFrustum, which drops
// casters that cannot project onto anything currently visible — but that test reads the view DIRECTION, so the set
// of drawn sections changes as the player turns
// This port's shadow pass also feeds a pack's voxelization for coloured lighting, and a section set that changes
// between frames makes the floodfill chase a moving voxel field. That showed up as permanent strobing on every
// coloured-lit surface. A box around the camera depends only on where the player is, so it stays frame-stable
// while still bounding the work
// shadow.culling = off skips this entirely; both `on` and `reversed` use it, since a box of the pack's own
// shadowDistance is a conservative superset of what either Iris mode would have kept
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
