package com.bdmajora.impetus.umbra.pipeline.shadow;

import org.joml.Matrix4fc;
import org.joml.Vector3f;

// AdvancedShadowCullingFrustum with an inner voxelDistance box drawn unconditionally (shadow.culling = reversed); the stable inner set lets a pack's floodfill voxelise as the player turns
public final class SafeZoneCullingFrustum extends AdvancedShadowCullingFrustum {
    private final ShadowBoxCuller distanceCuller;

    // voxelCuller is the inner safe zone (always drawn), distanceCuller the outer bound (always culled); no voxelDistance means a zero-size zone, degrading to plain advanced culling
    public SafeZoneCullingFrustum(Matrix4fc modelViewProjection, Vector3f shadowLightVectorFromOrigin,
                                  ShadowBoxCuller voxelCuller, ShadowBoxCuller distanceCuller) {
        super(modelViewProjection, shadowLightVectorFromOrigin, voxelCuller);
        this.distanceCuller = distanceCuller;
    }

    // Always visible inside the safe distance, otherwise defers to the advanced frustum
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
