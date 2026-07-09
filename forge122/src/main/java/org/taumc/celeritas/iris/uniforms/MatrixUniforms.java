package org.taumc.celeritas.iris.uniforms;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;
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
                // Plain inverse, no translation surgery: OptiFine 1.12.2 uploads the raw inverse of the modelview it
                // captured after setupCameraTransform (Shaders.setCamera), and the 1.12.2 modelview at that point has
                // no world translation (camera rotation + small eye offsets only), so the inverse is already the
                // ViewToPlayer matrix packs expect. (The earlier m30/m31/m32 zeroing deviated from OptiFine; the
                // m03/m13/m23 variant zeroed the always-zero bottom row — JOML's mCR is column-row — i.e. a no-op.)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "gbufferModelViewInverse",
                        () -> new Matrix4f(state.getGbufferModelView()).invert())
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
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "previousCameraPosition", () -> toVector3f(state.getPreviousCameraPosition()))
                // Iris's precise camera-position split (CameraUniforms): the integer and fractional parts are computed
                // in DOUBLE precision on the CPU, so the fractional part stays exact ([0,1)) no matter how far from the
                // world origin the camera is. Complementary's voxelization (SceneToVoxel uses cameraPositionBestFract)
                // switches to this path when IRIS_VERSION is defined; the OptiFine float `fract(cameraPosition)` path
                // loses low bits at large coordinates, so the voxel grid boundary wobbles frame-to-frame and colored
                // lighting shimmers along block edges. Providing these + defining IRIS_VERSION removes that.
                .uniform3i(UniformUpdateFrequency.PER_FRAME, "cameraPositionInt", () -> toIntPart(state.getCameraPosition()))
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "cameraPositionFract", () -> toFractPart(state.getCameraPosition()))
                .uniform3i(UniformUpdateFrequency.PER_FRAME, "previousCameraPositionInt", () -> toIntPart(state.getPreviousCameraPosition()))
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "previousCameraPositionFract", () -> toFractPart(state.getPreviousCameraPosition()));
    }

    private static Vector3f toVector3f(Vector3d position) {
        return new Vector3f((float) position.x, (float) position.y, (float) position.z);
    }

    private static Vector3i toIntPart(Vector3d position) {
        return new Vector3i((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z));
    }

    private static Vector3f toFractPart(Vector3d position) {
        return new Vector3f(
                (float) (position.x - Math.floor(position.x)),
                (float) (position.y - Math.floor(position.y)),
                (float) (position.z - Math.floor(position.z)));
    }
}
