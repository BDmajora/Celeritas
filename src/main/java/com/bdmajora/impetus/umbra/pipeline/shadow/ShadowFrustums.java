package com.bdmajora.impetus.umbra.pipeline.shadow;

import com.bdmajora.impetus.engine.impl.render.viewport.frustum.Frustum;
import com.bdmajora.impetus.umbra.pipeline.ShadowContentSettings;
import com.bdmajora.impetus.umbra.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

// Picks the shadow section filter following Iris's decision tree: distance-only when the pack voxelises and
// stated no preference, SafeZoneCullingFrustum when it asked for reversed, the advanced frustum otherwise
public final class ShadowFrustums {

    // Accepts every section. Iris returns its NonCullingFrustum in exactly two cases — the pack turned culling off,
    // and a distance-only pass whose distance already covers the render distance so the box would exclude nothing
    // Every other branch returns a real frustum, at most dropping its box culler
    public static final Frustum NON_CULLING = (minX, minY, minZ, maxX, maxY, maxZ) -> true;

    private ShadowFrustums() {
    }

    // shadowDistance and voxelDistance are the pack's own directives in blocks, voxelDistance being 0 when it
    // declares none
    // packVoxelizes is inferred rather than declared: true when the shadow pass has a geometry stage or custom
    // images, which is what voxelizing packs use
    // renderDistance is the player's setting in blocks, and sunPathRotation feeds the shadow light vector
    public static Frustum create(ShadowContentSettings.Culling culling, float shadowDistance, float voxelDistance,
                                 boolean packVoxelizes, int renderDistance, float sunPathRotation) {
        // Culling explicitly off: draw it all.
        if (culling == ShadowContentSettings.Culling.OFF) {
            return NON_CULLING;
        }

        // Umbra parity: a voxelizing pack that did not ask for a specific mode gets distance-only culling, because
        // the advanced frustum's view dependence would destabilize its voxel field. This is the ONLY branch in
        // which Umbra degrades to no culling at all when the distance already covers the render distance — its
        // `distance <= 0 || distance > renderDistance` test guards the NonCullingFrustum return and nothing else.
        if (culling == ShadowContentSettings.Culling.ON && packVoxelizes) {
            if (shadowDistance <= 0.0f || shadowDistance > renderDistance) {
                return NON_CULLING;
            }
            return new ShadowBoxCuller(shadowDistance);
        }

        Vector3f lightVector = shadowLightVectorFromOrigin(sunPathRotation);
        Matrix4f projView = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection())
                .mul(CapturedRenderingState.INSTANCE.getGbufferModelView());

        if (culling == ShadowContentSettings.Culling.REVERSED) {
            // `reversed`/`safe_zone`: everything within voxelDistance is drawn unconditionally, and shadowDistance
            // is the hard outer bound. Umbra uses voxelDistance verbatim — a pack that declares none gets a
            // degenerate (zero-size) safe zone, i.e. plain advanced culling, so that is reproduced rather than
            // substituting the shadow distance.
            //
            // Umbra exempts this mode from the "distance covers the render distance" bailout outright — that test
            // is `distance >= renderDistance && !hasSafeZone` — and measures it against voxelDistance rather than
            // shadowDistance, so both box cullers are always built. Applying the bailout here instead collapsed
            // the whole frustum to NON_CULLING for any pack whose shadowDistance exceeds the render distance
            // (Complementary's 256 over anything under 16 chunks), handing the shadow pass every loaded section
            // in place of a voxelDistance-sized safe zone.
            return new SafeZoneCullingFrustum(projView, lightVector,
                    new ShadowBoxCuller(voxelDistance), new ShadowBoxCuller(shadowDistance));
        }

        // Umbra drops only the *box* culler when the shadow distance covers the render distance; the
        // direction-dependent planes still apply, which is what keeps off-screen casters casting. Both frustums
        // treat a null culler as "no distance bound".
        if (shadowDistance <= 0.0f || shadowDistance >= renderDistance) {
            return new AdvancedShadowCullingFrustum(projView, lightVector, null);
        }

        return new AdvancedShadowCullingFrustum(projView, lightVector, new ShadowBoxCuller(shadowDistance));
    }

    // The normalised vector from the origin toward the shadow light, which is what decides the frustum's "back"
    // planes — the ones that keep casters behind the camera in the shadow map
    // Derived the same way CelestialUniforms.getShadowLightPositionInWorldSpace does
    @SuppressWarnings("unused") // sunPathRotation is already baked into CelestialUniforms' static state
    private static Vector3f shadowLightVectorFromOrigin(float sunPathRotation) {
        Vector3f vector = com.bdmajora.impetus.umbra.uniforms.CelestialUniforms
                .getShadowLightPositionInWorldSpace();
        if (vector.lengthSquared() == 0.0f) {
            // Degenerate (no celestial state yet): pick straight up so the frustum stays well-formed.
            return new Vector3f(0.0f, 1.0f, 0.0f);
        }
        return vector.normalize();
    }
}
