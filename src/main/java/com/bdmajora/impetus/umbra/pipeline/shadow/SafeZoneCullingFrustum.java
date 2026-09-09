package com.bdmajora.impetus.umbra.pipeline.shadow;

import org.joml.Matrix4fc;
import org.joml.Vector3f;

// AdvancedShadowCullingFrustum with a guaranteed "safe zone" — Iris's
// shadows.frustum.advanced.SafeZoneCullingFrustum, selected by shadow.culling = reversed
// Two boxes bound the test. The distance box (shadowDistance) is a hard outer bound: outside it nothing is drawn
// at all. The safe zone box (voxelDistance) is an inner region where everything is drawn UNCONDITIONALLY, skipping
// the view-dependent frustum test
// That inner box is what makes this mode usable for packs that voxelize in the shadow pass: inside it the drawn
// section set depends only on camera position, so a pack's floodfill sees a stable voxel field as the player
// turns. Outside it the advanced test applies and saves the work
public final class SafeZoneCullingFrustum extends AdvancedShadowCullingFrustum {
    private final ShadowBoxCuller distanceCuller;

    // voxelCuller is the inner safe zone: anything inside it is always drawn
    // distanceCuller is the outer bound: anything outside it is always culled
    // A pack that declares no voxelDistance gets a zero-size safe zone, which degrades to plain advanced culling
    // rather than to an error
    public SafeZoneCullingFrustum(Matrix4fc modelViewProjection, Vector3f shadowLightVectorFromOrigin,
                                  ShadowBoxCuller voxelCuller, ShadowBoxCuller distanceCuller) {
        super(modelViewProjection, shadowLightVectorFromOrigin, voxelCuller);
        this.distanceCuller = distanceCuller;
    }

    @Override
    public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        // NB: ShadowBoxCuller.testAab is "true if visible", the inverse of Umbra's isCulled.
        if (this.distanceCuller != null && !this.distanceCuller.testAab(minX, minY, minZ, maxX, maxY, maxZ)) {
            return false;
        }
        if (this.boxCuller != null && this.boxCuller.testAab(minX, minY, minZ, maxX, maxY, maxZ)) {
            return true;
        }
        return checkCornerVisibility(minX, minY, minZ, maxX, maxY, maxZ) != OUTSIDE;
    }
}
