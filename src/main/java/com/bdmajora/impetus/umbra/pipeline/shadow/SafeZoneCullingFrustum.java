package com.bdmajora.impetus.umbra.pipeline.shadow;

import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * {@link AdvancedShadowCullingFrustum} with a guaranteed "safe zone" — Umbra's
 * {@code shadows.frustum.advanced.SafeZoneCullingFrustum}, selected by {@code shadow.culling = reversed}.
 * <p>
 * Two boxes bound the test:
 * <ul>
 * <li>the <b>distance</b> box ({@code shadowDistance}) is a hard outer bound — outside it, nothing is drawn;</li>
 * <li>the <b>safe zone</b> box ({@code voxelDistance}) is an inner region where everything is drawn
 * <em>unconditionally</em>, bypassing the view-dependent frustum test entirely.</li>
 * </ul>
 * That inner box is exactly what makes this mode safe for packs that voxelize in the shadow pass: within it, the
 * drawn section set depends only on camera position, so a pack's floodfill sees a stable voxel field as the player
 * turns. Outside it, the advanced test applies and saves the work.
 */
public final class SafeZoneCullingFrustum extends AdvancedShadowCullingFrustum {
    private final ShadowBoxCuller distanceCuller;

    /**
     * @param voxelCuller    the inner safe zone; anything inside is always drawn
     * @param distanceCuller the outer bound; anything outside is always culled
     */
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
