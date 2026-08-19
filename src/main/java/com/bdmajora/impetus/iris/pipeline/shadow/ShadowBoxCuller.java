package com.bdmajora.impetus.iris.pipeline.shadow;

import com.bdmajora.impetus.engine.impl.render.viewport.frustum.Frustum;

/**
 * Port of Iris's {@code shadows.frustum.BoxCuller} (its {@code isCulledSodium} form): keeps the shadow pass to a cube
 * of {@code shadowDistance} around the camera instead of walking every loaded section.
 * <p>
 * The engine hands the frustum <em>camera-relative</em> coordinates, so the test is a plain comparison against
 * ±maxDistance and needs no per-frame re-centring.
 * <p>
 * This is deliberately the <em>only</em> shadow culling implemented. Iris also ships
 * {@code AdvancedShadowCullingFrustum}, which additionally drops casters that cannot project onto anything currently
 * visible — but that test depends on the view direction, so the drawn section set changes as the player turns. This
 * port's shadow pass also feeds a pack's voxelization (colored lighting), and a section set that changes between
 * frames makes the floodfill chase a moving voxel field, which previously showed up as permanent strobing on every
 * colored-lit surface. A box around the camera depends only on position, so it stays frame-stable while still
 * bounding the work.
 * <p>
 * {@code shadow.culling = off} bypasses this entirely; {@code on} and {@code reversed} both use it, since a box of
 * the pack's own {@code shadowDistance} is a conservative superset of what either Iris mode would keep.
 */
public final class ShadowBoxCuller implements Frustum {
    private final float maxDistance;

    public ShadowBoxCuller(float maxDistance) {
        this.maxDistance = maxDistance;
    }

    /** {@inheritDoc} The engine's contract is "true if visible", the inverse of Iris's {@code isCulled}. */
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
