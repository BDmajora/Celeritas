package org.taumc.celeritas.iris.uniforms;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.taumc.celeritas.iris.gl.program.ProgramUniforms;
import org.taumc.celeritas.iris.gl.uniform.UniformUpdateFrequency;

/**
 * The camera-matrix uniforms ({@code gbufferModelView(Inverse)}, {@code gbufferProjection(Inverse)}, the
 * {@code gbufferPrevious*} pair, and {@code cameraPosition}/{@code previousCameraPosition}), all read from
 * {@link CapturedRenderingState} — which the {@code EntityRenderer} mixin fills in each frame from the matrices vanilla
 * itself captured in {@code ActiveRenderInfo.updateRenderInfo} right after {@code setupCameraTransform}.
 */
public final class MatrixUniforms {
    private MatrixUniforms() {
    }

    public static void addMatrixUniforms(ProgramUniforms.Builder uniforms) {
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        uniforms
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "gbufferModelView", state::getGbufferModelView)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "gbufferModelViewInverse", () -> {
                    Matrix4f inv = new Matrix4f(state.getGbufferModelView()).invert();
                    // Forge 1.12.2 uses absolute world coordinates (gl_Vertex = P) and the modelview
                    // matrix includes camera translation: gbufferModelView = [R | -R*C]. The inverse
                    // therefore has [R^T | C] in its last column, making ViewToPlayer() return P instead
                    // of the camera-relative position (P - C) that modern shader packs expect.
                    // Zeroing the translation column makes ViewToPlayer() camera-relative, matching
                    // the Iris 1.17+ convention that Complementary et al. are written against.
                    inv.m30(0.0f);
                    inv.m31(0.0f);
                    inv.m32(0.0f);
                    return inv;
                })
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "gbufferProjection", state::getGbufferProjection)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "gbufferProjectionInverse",
                        () -> new Matrix4f(state.getGbufferProjection()).invert())
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "gbufferPreviousModelView", state::getPreviousGbufferModelView)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "gbufferPreviousProjection", state::getPreviousGbufferProjection)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "shadowModelView", state::getShadowModelView)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "shadowModelViewInverse",
                        () -> new Matrix4f(state.getShadowModelView()).invert())
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "shadowProjection", state::getShadowProjection)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "shadowProjectionInverse",
                        () -> new Matrix4f(state.getShadowProjection()).invert())
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "cameraPosition", () -> toVector3f(state.getCameraPosition()))
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "previousCameraPosition", () -> toVector3f(state.getPreviousCameraPosition()));
    }

    private static Vector3f toVector3f(Vector3d position) {
        return new Vector3f((float) position.x, (float) position.y, (float) position.z);
    }
}
