package com.bdmajora.impetus.umbra.uniforms;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import com.bdmajora.impetus.umbra.gl.program.ProgramUniforms;
import com.bdmajora.impetus.umbra.gl.uniform.UniformCollector;
import com.bdmajora.impetus.umbra.gl.uniform.UniformUpdateFrequency;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.function.Supplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The camera-matrix uniforms ({@code gbufferModelView(Inverse)}, {@code gbufferProjection(Inverse)}, the
 * {@code gbufferPrevious*} pair, and {@code cameraPosition}/{@code previousCameraPosition}), all read from
 * {@link CapturedRenderingState} and {@link CameraUniforms}. The {@code EntityRenderer} mixin fills the matrices from
 * vanilla's own {@code ActiveRenderInfo} capture; {@code CameraUniforms} tracks the Umbra-compatible current/previous
 * camera positions once per rendered frame.
 */
public final class MatrixUniforms {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");
    /** One report per uniform name; a singular matrix repeats every frame until whatever caused it goes away. */
    private static final java.util.Set<String> REPORTED_SINGULAR = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Matrix4fc IDENTITY = new Matrix4f();
    private static final int GL_ACTIVE_TEXTURE = 0x84E0;
    private static final int GL_MATRIX_MODE = 0x0BA0;
    private static final int GL_TEXTURE_MODE = 0x1702;
    private static final int GL_TEXTURE_MATRIX = 0x0BA8;
    private static final int GL_MODELVIEW_MATRIX = 0x0BA6;
    private static final Matrix4fc LIGHTMAP_TEXTURE_MATRIX = new Matrix4f(
            0.00390625f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.00390625f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.00390625f, 0.0f,
            0.03125f, 0.03125f, 0.03125f, 1.0f);

    private MatrixUniforms() {
    }

    public static void addMatrixUniforms(UniformCollector uniforms) {
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        uniforms
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "gbufferModelView", state::getGbufferModelView)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "iris_ModelViewMatrix", state::getGbufferModelView)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "iris_ModelViewMat", state::getGbufferModelView)
                // Plain inverse, no translation surgery: OptiFine 1.12.2 uploads the raw inverse of the modelview it
                // captured after setupCameraTransform (Shaders.setCamera), and the 1.12.2 modelview at that point has
                // no world translation (camera rotation + small eye offsets only), so the inverse is already the
                // ViewToPlayer matrix packs expect. (The earlier m30/m31/m32 zeroing deviated from OptiFine; the
                // m03/m13/m23 variant zeroed the always-zero bottom row — JOML's mCR is column-row — i.e. a no-op.)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "gbufferModelViewInverse",
                        () -> invertedOrIdentity("gbufferModelViewInverse", state.getGbufferModelView()))
                // Stand-ins for `gl_ModelViewMatrixInverse`, and a different family from `gbufferModelViewInverse`
                // above: Umbra registers those pack-facing names PER_FRAME in its own MatrixUniforms, but declares
                // these through ExternallyManagedUniforms and uploads them PER DRAW in
                // ExtendedShader#umbra$setupState (`RenderSystem.getModelViewMatrix().invert()`), which carries the
                // pose stack. Deriving them from gbufferModelView keeps only the camera half, so every entity and
                // block entity loses its own model transform — same defect as the normal matrix below, same fix.
                .uniformMatrix(UniformUpdateFrequency.DYNAMIC, "iris_ModelViewMatrixInverse",
                        MatrixUniforms::getLiveModelViewInverse)
                .uniformMatrix(UniformUpdateFrequency.DYNAMIC, "iris_ModelViewMatInverse",
                        MatrixUniforms::getLiveModelViewInverse)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "gbufferProjection", state::getGbufferProjection)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "iris_ProjectionMatrix", state::getGbufferProjection)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "iris_ProjMat", state::getGbufferProjection)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "gbufferProjectionInverse",
                        () -> invertedOrIdentity("gbufferProjectionInverse", state.getGbufferProjection()))
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "dhProjection", state::getGbufferProjection)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "dhProjectionInverse",
                        () -> invertedOrIdentity("dhProjectionInverse", state.getGbufferProjection()))
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "dhPreviousProjection",
                        new Previous(state::getGbufferProjection))
                // Umbra swaps the shadow projection in here while the shadow pass runs
                // (ExtendedShader#umbra$setupState: `areShadowsCurrentlyBeingRendered() ? ShadowRenderer.PROJECTION
                // : getGbufferProjection()`). These two are the stand-ins for `gl_ProjectionMatrixInverse`, so in a
                // shadow program they must invert the shadow ortho — handing back the camera perspective inverse
                // there is simply the wrong matrix, and any pack that unprojects depth inside shadow.vsh/fsh gets
                // garbage positions from it. The pack-facing `gbufferProjectionInverse` above deliberately does NOT
                // switch: that name means the camera projection by definition, and Umbra keeps it camera-only too.
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "iris_ProjectionMatrixInverse",
                        MatrixUniforms::getActiveProjectionInverse)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "iris_ProjMatInverse",
                        MatrixUniforms::getActiveProjectionInverse)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "u_ModelViewProjectionMatrix",
                        () -> new Matrix4f(state.getGbufferProjection()).mul(state.getGbufferModelView()))
                // Umbra computes the normal matrix PER DRAW from that draw's own modelview
                // (ExtendedShader#setupUniforms: `RenderSystem.getModelViewMatrix().invert().transpose3x3()`), which
                // on 1.16+ already carries the pose stack — i.e. camera AND model transform. Deriving it from
                // gbufferModelView instead keeps only the camera half, so every entity loses its own rotation and
                // scale and the matrix ends up varying with camera yaw and nothing else. PER_FRAME compounds that:
                // one value is shared by every object in the frame, which cannot be right the moment two objects
                // have different model transforms.
                //
                // DYNAMIC + reading the live fixed-function modelview is the 1.12 equivalent of Umbra's per-draw
                // upload: on the compatibility profile that matrix IS camera x model at the moment of the draw,
                // which is exactly what `RenderSystem.getModelViewMatrix()` returns upstream. iris_TextureMat in
                // this same builder already uses that pattern.
                .uniformMatrix3(UniformUpdateFrequency.DYNAMIC, "iris_DefaultNormalMat",
                        MatrixUniforms::getLiveNormalMatrix)
                .uniformMatrix3(UniformUpdateFrequency.DYNAMIC, "iris_NormalMatrix",
                        MatrixUniforms::getLiveNormalMatrix)
                .uniformMatrix3(UniformUpdateFrequency.DYNAMIC, "iris_NormalMat",
                        MatrixUniforms::getLiveNormalMatrix)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "iris_DefaultModelViewMatrixInverse",
                        () -> invertedOrIdentity("iris_DefaultModelViewMatrixInverse", state.getGbufferModelView()))
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "iris_DefaultProjectionMatrixInverse",
                        () -> invertedOrIdentity("iris_DefaultProjectionMatrixInverse", state.getGbufferProjection()))
                .uniformMatrix(UniformUpdateFrequency.DYNAMIC, "iris_TextureMat",
                        MatrixUniforms::getDefaultTextureMatrix)
                .uniformMatrix(UniformUpdateFrequency.ONCE, "iris_LightmapTextureMatrix",
                        () -> LIGHTMAP_TEXTURE_MATRIX)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "gbufferPreviousModelView",
                        new Previous(state::getGbufferModelView))
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "gbufferPreviousProjection",
                        new Previous(state::getGbufferProjection))
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "shadowModelView", state::getShadowModelView)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "shadowModelViewInverse",
                        () -> invertedOrIdentity("shadowModelViewInverse", state.getShadowModelView()))
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "shadowProjection", state::getShadowProjection)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "shadowProjectionInverse",
                        () -> invertedOrIdentity("shadowProjectionInverse", state.getShadowProjection()))
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "iris_ShadowModelViewMatrixInverse",
                        () -> invertedOrIdentity("iris_ShadowModelViewMatrixInverse", state.getShadowModelView()))
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "iris_ShadowProjectionMatrixInverse",
                        () -> invertedOrIdentity("iris_ShadowProjectionMatrixInverse", state.getShadowProjection()))
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "cameraPosition",
                        () -> toVector3f(CameraUniforms.getCurrentCameraPosition()))
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "previousCameraPosition",
                        () -> toVector3f(CameraUniforms.getPreviousCameraPosition()))
                // Umbra's precise camera-position split (CameraUniforms): the integer and fractional parts are computed
                // in DOUBLE precision on the CPU, so the fractional part stays exact ([0,1)) no matter how far from the
                // world origin the camera is. Complementary's voxelization (SceneToVoxel uses cameraPositionBestFract)
                // switches to this path when UMBRA_VERSION is defined; the OptiFine float `fract(cameraPosition)` path
                // loses low bits at large coordinates, so the voxel grid boundary wobbles frame-to-frame and colored
                // lighting shimmers along block edges. Providing these + defining UMBRA_VERSION removes that.
                .uniform3i(UniformUpdateFrequency.PER_FRAME, "cameraPositionInt",
                        () -> CameraUniforms.getCameraPositionInt(CameraUniforms.getCurrentCameraPositionUnshifted()))
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "cameraPositionFract",
                        () -> CameraUniforms.getCameraPositionFract(CameraUniforms.getCurrentCameraPositionUnshifted()))
                .uniform3i(UniformUpdateFrequency.PER_FRAME, "previousCameraPositionInt",
                        () -> CameraUniforms.getCameraPositionInt(CameraUniforms.getPreviousCameraPositionUnshifted()))
                .uniform3f(UniformUpdateFrequency.PER_FRAME, "previousCameraPositionFract",
                        () -> CameraUniforms.getCameraPositionFract(CameraUniforms.getPreviousCameraPositionUnshifted()));
    }

    private static Vector3f toVector3f(Vector3d position) {
        return new Vector3f((float) position.x, (float) position.y, (float) position.z);
    }

    /**
     * Inverts {@code source}, substituting identity if the result is not finite.
     * <p>
     * Umbra inverts unguarded ({@code MatrixUniforms.Inverted}: {@code new Matrix4f(parent.get()).invert()}), and it can
     * afford to — every matrix it inverts comes from a live pose stack that is invertible by construction. This port
     * feeds the same code from 1.12.2 fixed-function readbacks ({@code ActiveRenderInfo}'s captured modelview,
     * {@code GL_MODELVIEW_MATRIX}, a shadow ortho built from pack directives), any of which can be singular — an
     * all-zero buffer captured before vanilla filled it, or a degenerate ortho. JOML's {@code invert()} divides by the
     * determinant, so a singular input yields {@code Inf}/{@code NaN} in all sixteen elements with no exception and no
     * GL error.
     * <p>
     * That is not a cosmetic failure. Complementary routes <em>every terrain vertex in both passes</em> through one of
     * these inverses — {@code position = gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex} in
     * {@code gbuffers_terrain}, {@code position = shadowModelViewInverse * shadowProjectionInverse * ftransform()} in
     * {@code shadow} — while entities use {@code gl_Position = ftransform()} and touch no inverse at all. One
     * non-finite inverse therefore makes {@code gl_Position} NaN for all terrain, the non-finite guard in
     * {@link com.bdmajora.impetus.umbra.terrain.ImpetusTerrainTransformer} collapses every one of those vertices behind
     * the far plane, and the world silently disappears while entities, block entities and particles keep drawing.
     * That is the recurring "world unloads randomly" report, and because the guard clips the vertex before any
     * fragment runs, its {@code iris_nanFlag} could never be observed.
     * <p>
     * Identity is wrong, but it is finite: one frame renders from the wrong basis instead of not rendering at all, and
     * the log names the uniform.
     */
    private static Matrix4fc invertedOrIdentity(String name, Matrix4fc source) {
        Matrix4f inverse = new Matrix4f(source).invert();
        if (isFinite(inverse)) {
            return inverse;
        }
        if (REPORTED_SINGULAR.add(name)) {
            LOGGER.error("[Umbra] '{}' inverted to a non-finite matrix; its source is singular, so every vertex "
                    + "transformed by it would be NaN. Substituting identity. Source was:\n{}", name, source);
        }
        return IDENTITY;
    }

    private static boolean isFinite(Matrix4fc matrix) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                if (!Float.isFinite(matrix.get(column, row))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * {@return the inverse-transpose of the modelview that is live <em>right now</em>, i.e. this draw's normal matrix}
     * <p>
     * Umbra's per-draw equivalent reads {@code RenderSystem.getModelViewMatrix()}, which on 1.16+ is the pose stack
     * (camera x model). On the compatibility profile the fixed-function {@code GL_MODELVIEW_MATRIX} holds the same
     * thing at draw time, so reading it back here reproduces Umbra's value rather than approximating it with the
     * camera matrix.
     */
    /** {@return the inverse of the modelview live <em>right now</em> — this draw's, not the frame's camera matrix} */
    private static Matrix4fc getLiveModelViewInverse() {
        return invertedOrIdentity("iris_ModelViewMatrixInverse", getLiveModelView());
    }

    /** {@return the fixed-function modelview as it stands at this instant, i.e. camera x model for the current draw} */
    private static Matrix4fc getLiveModelView() {
        FloatBuffer buffer = ByteBuffer.allocateDirect(16 * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        GlStateManager.getFloat(GL_MODELVIEW_MATRIX, buffer);
        buffer.rewind();
        return new Matrix4f().set(buffer);
    }

    /** {@return the inverse of whichever projection the pass currently running actually rasterises with} */
    private static Matrix4fc getActiveProjectionInverse() {
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        boolean shadow = com.bdmajora.impetus.umbra.pipeline.UmbraShadowRenderer.isShadowPass();
        Matrix4fc projection = shadow ? state.getShadowProjection() : state.getGbufferProjection();
        return invertedOrIdentity(shadow ? "iris_ProjectionMatrixInverse (shadow ortho)"
                : "iris_ProjectionMatrixInverse (camera projection)", projection);
    }

    /**
     * The normal matrix cannot delete geometry the way a position matrix can, but a non-finite one poisons every
     * lit fragment, so it goes through the same guard. Reusing {@link #invertedOrIdentity} keeps one report per name.
     */
    private static Matrix3fc getLiveNormalMatrix() {
        return new Matrix4f(invertedOrIdentity("iris_NormalMatrix", getLiveModelView()))
                .transpose3x3(new Matrix3f());
    }

    private static Matrix4fc getDefaultTextureMatrix() {
        int previousTexture = LWJGL.glGetInteger(GL_ACTIVE_TEXTURE);
        int previousMatrixMode = LWJGL.glGetInteger(GL_MATRIX_MODE);
        FloatBuffer buffer = ByteBuffer.allocateDirect(16 * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        try {
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.matrixMode(GL_TEXTURE_MODE);
            buffer.clear();
            GlStateManager.getFloat(GL_TEXTURE_MATRIX, buffer);
            buffer.rewind();
            return new Matrix4f().set(buffer);
        } finally {
            GlStateManager.matrixMode(previousMatrixMode);
            GlStateManager.setActiveTexture(previousTexture);
        }
    }

    /**
     * Supplies the previous frame's value of a matrix. State must roll forward exactly once per rendered frame, but
     * {@link #get()} is invoked once per <em>program</em> that declares the uniform (every gbuffers/deferred/composite
     * pass), many times within a single frame. Rolling on every call collapsed {@code previous} onto the current
     * matrix after the first upload, so by the time the TAA composite ran, {@code gbufferPreviousModelView} equalled
     * {@code gbufferModelView}: reprojection produced zero motion and TAA smeared history over moving geometry.
     * Guard the roll on {@code frameCounter} so every pass in a frame sees the same, genuinely-previous matrix.
     */
    private static final class Previous implements Supplier<Matrix4fc> {
        private final Supplier<Matrix4fc> parent;
        private final Matrix4f previous = new Matrix4f();
        private final Matrix4f current = new Matrix4f();
        private int lastFrame = -1;

        private Previous(Supplier<Matrix4fc> parent) {
            this.parent = parent;
        }

        @Override
        public Matrix4fc get() {
            int frame = SystemTimeUniforms.COUNTER.getFrameCounter();
            if (frame != this.lastFrame) {
                this.previous.set(this.current);
                this.current.set(this.parent.get());
                if (this.lastFrame == -1) {
                    // First frame: no genuine history yet, so avoid a bogus one-frame jump.
                    this.previous.set(this.current);
                }
                this.lastFrame = frame;
            }
            return new Matrix4f(this.previous);
        }
    }
}
