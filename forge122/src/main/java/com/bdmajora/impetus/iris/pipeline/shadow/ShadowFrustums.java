package com.bdmajora.impetus.iris.pipeline.shadow;

import com.bdmajora.impetus.engine.impl.render.viewport.frustum.Frustum;
import com.bdmajora.impetus.iris.pipeline.ShadowContentSettings;
import com.bdmajora.impetus.iris.uniforms.CapturedRenderingState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Picks the shadow pass's section filter, following Iris's {@code ShadowRenderer.createShadowFrustum} decision tree.
 * <p>
 * The important subtlety is the interaction with voxelization. Iris falls back to distance-only culling when
 * {@code packCullingState == DEFAULT && packHasVoxelization}, because the advanced frustum is view-direction
 * dependent and an unstable section set makes a pack's floodfill chase a moving voxel field. A pack that explicitly
 * asks for {@code shadow.culling = reversed} instead gets {@link SafeZoneCullingFrustum}, whose inner
 * {@code voxelDistance} box is drawn unconditionally — that is the pack telling us where its voxelization needs
 * stability, so the advanced test can safely apply outside it.
 */
public final class ShadowFrustums {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    /** Accepts every section; used when culling is off or the shadow distance exceeds the render distance. */
    public static final Frustum NON_CULLING = (minX, minY, minZ, maxX, maxY, maxZ) -> true;

    private ShadowFrustums() {
    }

    /**
     * @param shadowDistance  the pack's {@code shadowDistance}, in blocks
     * @param voxelDistance   the pack's {@code voxelDistance}, or 0 when it declares none
     * @param packVoxelizes   true when the pack's shadow pass voxelizes (geometry stage or custom images present)
     * @param renderDistance  the player's render distance, in blocks
     * @param sunPathRotation for deriving the shadow light vector
     */
    public static Frustum create(ShadowContentSettings.Culling culling, float shadowDistance, float voxelDistance,
                                 boolean packVoxelizes, int renderDistance, float sunPathRotation) {
        // Culling explicitly off, or a shadow distance that covers everything anyway: draw it all.
        if (culling == ShadowContentSettings.Culling.OFF || shadowDistance <= 0.0f
                || shadowDistance > renderDistance) {
            LOGGER.info("[Iris] Shadow culling: disabled ({})",
                    culling == ShadowContentSettings.Culling.OFF
                            ? "set by shader pack" : "shadow distance covers the render distance");
            return NON_CULLING;
        }

        // Iris parity: a voxelizing pack that did not ask for a specific mode gets distance-only culling, because
        // the advanced frustum's view dependence would destabilize its voxel field.
        if (culling == ShadowContentSettings.Culling.ON && packVoxelizes) {
            LOGGER.info("[Iris] Shadow culling: distance only, {} blocks (voxelization detected)", shadowDistance);
            return new ShadowBoxCuller(shadowDistance);
        }

        Vector3f lightVector = shadowLightVectorFromOrigin(sunPathRotation);
        Matrix4f projView = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection())
                .mul(CapturedRenderingState.INSTANCE.getGbufferModelView());

        if (culling == ShadowContentSettings.Culling.REVERSED) {
            // `reversed`/`safe_zone`: everything within voxelDistance is drawn unconditionally, and shadowDistance
            // is the hard outer bound. Iris uses voxelDistance verbatim — a pack that declares none gets a
            // degenerate (zero-size) safe zone, i.e. plain advanced culling, so that is reproduced rather than
            // substituting the shadow distance.
            LOGGER.info("[Iris] Shadow culling: safe-zone frustum, {} block safe zone inside {} blocks",
                    voxelDistance, shadowDistance);
            return new SafeZoneCullingFrustum(projView, lightVector,
                    new ShadowBoxCuller(voxelDistance), new ShadowBoxCuller(shadowDistance));
        }

        LOGGER.info("[Iris] Shadow culling: advanced frustum, {} blocks", shadowDistance);
        return new AdvancedShadowCullingFrustum(projView, lightVector, new ShadowBoxCuller(shadowDistance));
    }

    /**
     * The normalized vector from the origin toward the shadow light, which is what decides the "back" planes.
     * Derived the same way {@code CelestialUniforms.getShadowLightPositionInWorldSpace} does.
     */
    @SuppressWarnings("unused") // sunPathRotation is already baked into CelestialUniforms' static state
    private static Vector3f shadowLightVectorFromOrigin(float sunPathRotation) {
        Vector3f vector = com.bdmajora.impetus.iris.uniforms.CelestialUniforms
                .getShadowLightPositionInWorldSpace();
        if (vector.lengthSquared() == 0.0f) {
            // Degenerate (no celestial state yet): pick straight up so the frustum stays well-formed.
            return new Vector3f(0.0f, 1.0f, 0.0f);
        }
        return vector.normalize();
    }
}
