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

    /**
     * Accepts every section. Iris returns its {@code NonCullingFrustum} in exactly two cases: the pack turned
     * culling off, and a distance-only pass whose distance already covers the render distance. Every other branch
     * returns a real frustum, dropping at most its box culler.
     */
    public static final Frustum NON_CULLING = (minX, minY, minZ, maxX, maxY, maxZ) -> true;

    /**
     * The last culling decision logged. {@link #create} runs once per frame, and logging every call flooded the log
     * with thousands of identical INFO lines per minute (measured: 6637 lines in a 90-second session, ~74/s). Each
     * one is a synchronous log4j write to file and console, which stalls the client badly enough to look like a
     * freeze. The decision string embeds the distances, so any change that matters still prints; the only thing lost
     * is a repeat line when a reload lands on an identical decision.
     */
    private static String lastLoggedDecision;

    private ShadowFrustums() {
    }

    private static void logDecision(String decision) {
        if (!decision.equals(lastLoggedDecision)) {
            lastLoggedDecision = decision;
            LOGGER.info("[Iris] Shadow culling: {}", decision);
        }
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
        // Diagnostic override: -Dimpetus.shadow.culling=off forces every section into the shadow map.
        //
        // Shadow culling is the last view-dependent input to the shadow map — the advanced frustum is built from
        // this frame's camera matrices, so which casters reach the shadow map genuinely changes as the camera turns.
        // That is faithful to Iris (ShadowRenderer#createShadowFrustum takes the same branch for a non-voxelizing
        // pack with no `shadow.culling` directive), which makes it impossible to tell by reading whether a
        // brightness-changes-with-heading symptom comes from culling being wrong or from something downstream.
        // Forcing it off answers that in one launch: if the symptom survives, the shadow pass is exonerated and the
        // cause is in the deferred/composite chain.
        if ("off".equalsIgnoreCase(System.getProperty("impetus.shadow.culling"))) {
            logDecision("disabled (forced by -Dimpetus.shadow.culling=off)");
            return NON_CULLING;
        }

        // Culling explicitly off: draw it all.
        if (culling == ShadowContentSettings.Culling.OFF) {
            logDecision("disabled (set by shader pack)");
            return NON_CULLING;
        }

        // Iris parity: a voxelizing pack that did not ask for a specific mode gets distance-only culling, because
        // the advanced frustum's view dependence would destabilize its voxel field. This is the ONLY branch in
        // which Iris degrades to no culling at all when the distance already covers the render distance — its
        // `distance <= 0 || distance > renderDistance` test guards the NonCullingFrustum return and nothing else.
        if (culling == ShadowContentSettings.Culling.ON && packVoxelizes) {
            if (shadowDistance <= 0.0f || shadowDistance > renderDistance) {
                logDecision("disabled (voxelization detected, shadow distance covers the render distance)");
                return NON_CULLING;
            }
            logDecision("distance only, " + shadowDistance + " blocks (voxelization detected)");
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
            //
            // Iris exempts this mode from the "distance covers the render distance" bailout outright — that test
            // is `distance >= renderDistance && !hasSafeZone` — and measures it against voxelDistance rather than
            // shadowDistance, so both box cullers are always built. Applying the bailout here instead collapsed
            // the whole frustum to NON_CULLING for any pack whose shadowDistance exceeds the render distance
            // (Complementary's 256 over anything under 16 chunks), handing the shadow pass every loaded section
            // in place of a voxelDistance-sized safe zone.
            logDecision("safe-zone frustum, " + voxelDistance + " block safe zone inside "
                    + shadowDistance + " blocks");
            return new SafeZoneCullingFrustum(projView, lightVector,
                    new ShadowBoxCuller(voxelDistance), new ShadowBoxCuller(shadowDistance));
        }

        // Iris drops only the *box* culler when the shadow distance covers the render distance; the
        // direction-dependent planes still apply, which is what keeps off-screen casters casting. Both frustums
        // treat a null culler as "no distance bound".
        if (shadowDistance <= 0.0f || shadowDistance >= renderDistance) {
            logDecision("advanced frustum, no distance bound (render distance " + renderDistance + " blocks)");
            return new AdvancedShadowCullingFrustum(projView, lightVector, null);
        }

        logDecision("advanced frustum, " + shadowDistance + " blocks");
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
