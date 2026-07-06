package org.taumc.celeritas.iris.pipeline;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.BlockRenderLayer;
import org.embeddedt.embeddium.impl.gl.device.RenderDevice;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.taumc.celeritas.impl.render.terrain.CeleritasWorldRenderer;
import org.taumc.celeritas.iris.gl.framebuffer.IrisFramebuffer;
import org.taumc.celeritas.iris.targets.DepthTexture;
import org.taumc.celeritas.iris.uniforms.CapturedRenderingState;
import org.taumc.celeritas.iris.uniforms.CelestialUniforms;
import org.taumc.celeritas.lwjgl.GL11;
import org.taumc.celeritas.lwjgl.GL14;
import org.taumc.celeritas.lwjgl.GL30;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The shadow-map pass: renders the terrain a second time each frame from the sun's (or moon's) point of view into a
 * depth texture ({@code shadowtex0/1}) using the pack's {@code shadow} program, before the main gbuffer pass samples
 * it. The shadow camera is modern Iris's construction — an orthographic frustum of {@code shadowDistance} half-extent,
 * rotated by the shadow angle, snapped to {@code shadowIntervalSize} world intervals so texels don't swim — which is
 * OptiFine's {@code setCamera} math without the fixed-function matrix stack.
 * <p>
 * Terrain is re-drawn through the normal Embeddium path ({@code drawChunkLayer}) with two switches thrown while
 * {@link #isShadowPass()}: {@code CeleritasWorldRenderer.createChunkRenderMatrices} returns the shadow matrices, and
 * {@code MixinShaderChunkRenderer} substitutes the pack's {@code shadow} program. The render lists are the player-view
 * lists (no separate shadow culling — OptiFine parity for its default config).
 */
public class IrisShadowRenderer {
    private static final Logger LOGGER = LogManager.getLogger("Celeritas/Iris");

    /** True while the shadow pass is drawing; consulted by the matrix/program/draw-buffer seams. */
    private static boolean shadowPassActive;

    private final int resolution;
    private final float halfPlaneLength;
    private final float sunPathRotation;
    private final float intervalSize = 2.0f;

    private final DepthTexture depthTexture;
    private final int colorTexture;
    private final IrisFramebuffer framebuffer;
    private int failureCount;
    private boolean failed;
    private boolean destroyed;

    private final Matrix4f shadowModelView = new Matrix4f();
    private final Matrix4f shadowProjection = new Matrix4f();

    public IrisShadowRenderer(int resolution, float shadowDistance, float sunPathRotation) {
        this.resolution = resolution;
        this.halfPlaneLength = shadowDistance;
        this.sunPathRotation = sunPathRotation;

        this.depthTexture = new DepthTexture(resolution, resolution,
                GL14.GL_DEPTH_COMPONENT24, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT);
        // Hardware compare so sampler2DShadow lookups return an in-shadow test result, matching the always-lit stub.
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.depthTexture.getTextureId());
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL14.GL_COMPARE_R_TO_TEXTURE);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        // shadowcolor0: the shadow program's color output (white where nothing draws = untinted shadows).
        this.colorTexture = LWJGL.glGenTextures();
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.colorTexture);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        LWJGL.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, resolution, resolution, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        this.framebuffer = new IrisFramebuffer();
        this.framebuffer.addColorAttachment(0, this.colorTexture);
        this.framebuffer.addDepthAttachment(this.depthTexture.getTextureId());
        this.framebuffer.drawBuffers(new int[]{0});

        LOGGER.info("[Iris] Shadow map ready: {}x{}, distance {}", resolution, resolution, shadowDistance);
    }

    public static boolean isShadowPass() {
        return shadowPassActive;
    }

    public int getDepthTextureId() {
        return this.depthTexture.getTextureId();
    }

    public int getColorTextureId() {
        return this.colorTexture;
    }

    public int getResolution() {
        return this.resolution;
    }

    /**
     * Renders the shadow map for this frame. Called right after the camera matrices are captured (so the shadow angle
     * and camera position are current) and before any world geometry draws into the gbuffer. The caller rebinds the
     * gbuffer/viewport afterwards.
     */
    public void render() {
        if (this.failed || this.destroyed) {
            return;
        }
        CeleritasWorldRenderer worldRenderer = CeleritasWorldRenderer.instanceNullable();
        if (worldRenderer == null) {
            return;
        }

        computeMatrices();
        CapturedRenderingState.INSTANCE.setShadowModelView(this.shadowModelView);
        CapturedRenderingState.INSTANCE.setShadowProjection(this.shadowProjection);

        try {
            shadowPassActive = true;

            this.framebuffer.bind();
            LWJGL.glViewport(0, 0, this.resolution, this.resolution);
            // shadowcolor clears to white (no tint); GlStateManager keeps the vanilla clear-color cache coherent.
            GlStateManager.clearColor(1.0f, 1.0f, 1.0f, 1.0f);
            LWJGL.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            GlStateManager.disableBlend();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            // The shadow program alpha-tests foliage against the block atlas; make sure it is what unit 0 holds
            // (this runs before vanilla's own "prepareterrain" atlas bind).
            net.minecraft.client.Minecraft.getMinecraft().getTextureManager()
                    .bindTexture(net.minecraft.client.renderer.texture.TextureMap.LOCATION_BLOCKS_TEXTURE);

            Vector3d camera = CapturedRenderingState.INSTANCE.getCameraPosition();
            // Embeddium requires draws to run inside its managed-device scope (the vanilla path enters it in
            // RenderGlobalMixin before every drawChunkLayer).
            RenderDevice.enterManagedCode();
            try {
                worldRenderer.drawChunkLayer(BlockRenderLayer.SOLID, camera.x, camera.y, camera.z);
                worldRenderer.drawChunkLayer(BlockRenderLayer.CUTOUT_MIPPED, camera.x, camera.y, camera.z);
                worldRenderer.drawChunkLayer(BlockRenderLayer.CUTOUT, camera.x, camera.y, camera.z);
            } finally {
                RenderDevice.exitManagedCode();
            }
        } catch (Throwable t) {
            // The very first frames can race renderer setup (no viewport/render lists yet) — only give up for good
            // after repeated failures.
            if (++this.failureCount >= 3) {
                this.failed = true;
                LOGGER.error("[Iris] Shadow pass failed repeatedly; disabling shadows for this pack", t);
            }
        } finally {
            shadowPassActive = false;
        }
    }

    /**
     * The Iris shadow camera, a verbatim port of {@code net.coderbot.iris.shadow.ShadowMatrices}
     * ({@code createModelViewMatrix} = {@code createBaselineModelViewMatrix} + {@code snapModelViewToGrid}) and
     * {@code createOrthoMatrix}. Mojang {@code PoseStack.multiply}/{@code mulPose} post-multiply, matching JOML's
     * {@code translate}/{@code rotate*}; {@code Vector3f.XP/ZP.rotationDegrees(d)} == {@code rotateX/Z(toRadians(d))}.
     * The grid snap (offset by the fractional camera position, centred by half a cell) is what keeps shadow-map
     * texels from swimming as the camera moves — the previous hand-rolled snap omitted the centring and had the wrong
     * sign, which is what made the shadows flicker.
     */
    private void computeMatrices() {
        // ShadowMatrices.createOrthoMatrix(halfPlaneLength): JOML setOrtho produces the identical matrix
        // (z-scale 2/(NEAR-FAR), z-translate -(FAR+NEAR)/(FAR-NEAR)) for NEAR=0.05, FAR=256.
        this.shadowProjection.identity().setOrtho(
                -this.halfPlaneLength, this.halfPlaneLength,
                -this.halfPlaneLength, this.halfPlaneLength,
                0.05f, 256.0f);

        // ---- createBaselineModelViewMatrix(target, shadowAngle, sunPathRotation) ----
        float shadowAngle = CelestialUniforms.getShadowAngle();
        float skyAngle;
        if (shadowAngle < 0.25f) {
            skyAngle = shadowAngle + 0.75f;
        } else {
            skyAngle = shadowAngle - 0.25f;
        }

        this.shadowModelView.identity()
                .translate(0.0f, 0.0f, -100.0f)
                .rotateX((float) Math.toRadians(90.0f))
                .rotateZ((float) Math.toRadians(skyAngle * -360.0f))
                .rotateX((float) Math.toRadians(this.sunPathRotation));

        // ---- snapModelViewToGrid(target, intervalSize, cameraX, cameraY, cameraZ) ----
        Vector3d camera = CapturedRenderingState.INSTANCE.getCameraPosition();
        float halfIntervalSize = this.intervalSize / 2.0f;
        float offsetX = (float) (camera.x - Math.floor(camera.x / this.intervalSize) * this.intervalSize) - halfIntervalSize;
        float offsetY = (float) (camera.y - Math.floor(camera.y / this.intervalSize) * this.intervalSize) - halfIntervalSize;
        float offsetZ = (float) (camera.z - Math.floor(camera.z / this.intervalSize) * this.intervalSize) - halfIntervalSize;
        this.shadowModelView.translate(offsetX, offsetY, offsetZ);
    }

    public Matrix4f getShadowModelView() {
        return this.shadowModelView;
    }

    public Matrix4f getShadowProjection() {
        return this.shadowProjection;
    }

    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        this.framebuffer.destroy();
        this.depthTexture.destroy();
        LWJGL.glDeleteTextures(this.colorTexture);
    }
}
