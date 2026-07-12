package org.taumc.celeritas.iris.pipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.embeddedt.embeddium.impl.gl.device.RenderDevice;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.taumc.celeritas.impl.render.terrain.CeleritasWorldRenderer;
import org.taumc.celeritas.iris.gl.framebuffer.IrisFramebuffer;
import org.taumc.celeritas.iris.gl.program.DrawBuffers;
import org.taumc.celeritas.iris.gl.shader.ShaderMacros;
import org.taumc.celeritas.iris.shaderpack.ProgramSource;
import org.taumc.celeritas.iris.targets.DepthTexture;
import org.taumc.celeritas.iris.uniforms.CapturedRenderingState;
import org.taumc.celeritas.iris.uniforms.CelestialUniforms;
import org.taumc.celeritas.lwjgl.GL11;
import org.taumc.celeritas.lwjgl.GL13;
import org.taumc.celeritas.lwjgl.GL14;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Map;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The shadow-map pass: renders the world a second time each frame from the sun's (or moon's) point of view, before
 * the main gbuffer pass samples the result. Follows Iris's {@code ShadowRenderer} geometry order exactly:
 * <ol>
 * <li>solid + cutout terrain (Embeddium path, the pack's {@code shadow} program substituted);</li>
 * <li>entities, then block entities — fixed-function, with the untransformed {@code shadow} program bound and the
 * shadow matrices loaded on the FF matrix stack (OptiFine renders its shadow entities the same way);</li>
 * <li>the depth buffer is copied to {@code shadowtex1} (the translucent-excluded depth, same construction as
 * {@code depthtex1});</li>
 * <li>translucent terrain, blended, writing color into {@code shadowcolor0}/{@code shadowcolor1} — colored/water
 * shadows — while its depth still lands in {@code shadowtex0}.</li>
 * </ol>
 * Hardware depth compare follows the pack's {@code const bool shadowHardwareFiltering[0/1]} declarations, OptiFine's
 * contract: LIGHT declares {@code shadowHardwareFiltering0} for its {@code shadow2D} lookups; Complementary declares
 * the both-textures form. Packs that declare nothing get raw depth reads.
 * <p>
 * The shadow camera is modern Iris's construction — an orthographic frustum of {@code shadowDistance} half-extent,
 * rotated by the shadow angle, snapped to {@code shadowIntervalSize} world intervals so texels don't swim.
 */
public class IrisShadowRenderer {
    private static final Logger LOGGER = LogManager.getLogger("Celeritas/Iris");

    // Fixed-function matrix modes (GlStateManager.matrixMode takes the raw GL enum).
    private static final int GL_MODELVIEW_MODE = 0x1700;
    private static final int GL_PROJECTION_MODE = 0x1701;

    /** True while the shadow pass is drawing; consulted by the matrix/program/draw-buffer seams. */
    private static boolean shadowPassActive;

    private final int resolution;
    private final float halfPlaneLength;
    private final float sunPathRotation;
    private final float intervalSize = 2.0f;

    /** shadowtex0: everything, translucents included. */
    private final DepthTexture depthTexture;
    /** shadowtex1: copied from shadowtex0 just before translucent shadow geometry draws. */
    private final DepthTexture depthTextureNoTranslucents;
    private final int colorTexture0;
    private final int colorTexture1;
    private final IrisFramebuffer framebuffer;
    /** Both shadowcolor attachments, for the frame-start clear. */
    private static final int[] CLEAR_MASK = {0, 1};
    /** The pack's shadow DRAWBUFFERS mask (only shadowcolor0/1 exist), applied for the geometry draws. */
    private final int[] shadowDrawBuffers;
    private final Runnable shaderPackResourceRestorer;
    /**
     * The fixed-function flavor of the pack's {@code shadow} program, for entities/block entities (immediate-mode
     * geometry — the Embeddium-format terrain shadow program cannot consume it). {@code null} if it failed to compile;
     * entity shadows are skipped then.
     */
    private final GbufferPrograms.Entry entityShadowProgram;

    private final FloatBuffer matrixBuffer =
            ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private int failureCount;
    private boolean failed;
    private boolean destroyed;

    private final Matrix4f shadowModelView = new Matrix4f();
    private final Matrix4f shadowProjection = new Matrix4f();

    /**
     * @param shadowSource      the pack's {@code shadow} program source (for the fixed-function entity flavor).
     * @param samplerUnits      the standard sampler-unit table (with gbuffers-stage custom-texture overrides applied).
     * @param hardwareFiltering per-texture {@code shadowHardwareFiltering} flags: [0] = shadowtex0, [1] = shadowtex1.
     */
    public IrisShadowRenderer(int resolution, float shadowDistance, float sunPathRotation,
                              ProgramSource shadowSource, Map<String, Integer> samplerUnits,
                              boolean[] hardwareFiltering, Runnable shaderPackResourceRestorer) {
        this.resolution = resolution;
        this.halfPlaneLength = shadowDistance;
        this.sunPathRotation = sunPathRotation;
        this.shaderPackResourceRestorer = shaderPackResourceRestorer;

        this.depthTexture = createShadowDepthTexture(resolution, hardwareFiltering[0]);
        this.depthTextureNoTranslucents = createShadowDepthTexture(resolution, hardwareFiltering[1]);

        // shadowcolor0/1: the shadow program's color outputs (white where nothing draws = untinted shadows).
        this.colorTexture0 = createShadowColorTexture(resolution);
        this.colorTexture1 = createShadowColorTexture(resolution);

        this.framebuffer = new IrisFramebuffer();
        this.framebuffer.addColorAttachment(0, this.colorTexture0);
        this.framebuffer.addColorAttachment(1, this.colorTexture1);
        this.framebuffer.addDepthAttachment(this.depthTexture.getTextureId());
        this.framebuffer.drawBuffers(CLEAR_MASK);

        // The shadow program's DRAWBUFFERS (0, or 01 when it also writes shadowcolor1); indices past the two
        // shadowcolor attachments would make the FBO mask reference missing images, so they are dropped.
        this.shadowDrawBuffers = shadowSource.getFragmentSource()
                .map(DrawBuffers::parseActive)
                .map(buffers -> DrawBuffers.sanitize(buffers, 2))
                .orElse(new int[]{0});

        this.entityShadowProgram = GbufferPrograms.compile(shadowSource, ShaderMacros.standard(), samplerUnits);
        if (this.entityShadowProgram == null) {
            LOGGER.warn("[Iris] Fixed-function shadow program failed to compile; entity shadows disabled");
        }

        LOGGER.info("[Iris] Shadow map ready: {}x{}, distance {}, hardware filtering [{}, {}]",
                resolution, resolution, shadowDistance, hardwareFiltering[0], hardwareFiltering[1]);
    }

    private static DepthTexture createShadowDepthTexture(int resolution, boolean hardwareFiltering) {
        DepthTexture texture = new DepthTexture(resolution, resolution,
                GL14.GL_DEPTH_COMPONENT24, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture.getTextureId());
        if (hardwareFiltering) {
            // sampler2DShadow lookups (shadow2D / textureProj) return an in-shadow test result.
            LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL14.GL_COMPARE_R_TO_TEXTURE);
            LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
        }
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    private static int createShadowColorTexture(int resolution) {
        int texture = LWJGL.glGenTextures();
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        LWJGL.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, resolution, resolution, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    public static boolean isShadowPass() {
        return shadowPassActive;
    }

    public int getDepthTextureId() {
        return this.depthTexture.getTextureId();
    }

    public int getDepthTextureNoTranslucentsId() {
        return this.depthTextureNoTranslucents.getTextureId();
    }

    public int getColorTextureId() {
        return this.colorTexture0;
    }

    public int getColorTexture1Id() {
        return this.colorTexture1;
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
            Minecraft mc = Minecraft.getMinecraft();

            this.framebuffer.bind();
            LWJGL.glViewport(0, 0, this.resolution, this.resolution);
            // shadowcolor clears to white (no tint); GlStateManager keeps the vanilla clear-color cache coherent.
            this.framebuffer.drawBuffers(CLEAR_MASK);
            GlStateManager.clearColor(1.0f, 1.0f, 1.0f, 1.0f);
            LWJGL.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            this.framebuffer.drawBuffers(this.shadowDrawBuffers);

            GlStateManager.disableBlend();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            // The shadow program alpha-tests foliage against the block atlas; make sure it is what unit 0 holds
            // (this runs before vanilla's own "prepareterrain" atlas bind).
            bindBlockAtlas(mc);

            Vector3d camera = CapturedRenderingState.INSTANCE.getCameraPosition();

            // 1) Solid + cutout terrain (Iris order). Embeddium requires draws inside its managed-device scope.
            RenderDevice.enterManagedCode();
            try {
                worldRenderer.drawChunkLayer(BlockRenderLayer.SOLID, camera.x, camera.y, camera.z);
                worldRenderer.drawChunkLayer(BlockRenderLayer.CUTOUT_MIPPED, camera.x, camera.y, camera.z);
                worldRenderer.drawChunkLayer(BlockRenderLayer.CUTOUT, camera.x, camera.y, camera.z);
            } finally {
                RenderDevice.exitManagedCode();
            }

            // 2) Entities + block entities, fixed-function under the shadow matrices.
            renderEntityShadows(mc, camera);

            // 3) shadowtex1 = depth without translucents (Iris copyPreTranslucentDepth).
            copyDepthTo(this.depthTextureNoTranslucents);

            // 4) Translucent terrain, blended: color tints shadowcolor0 (colored/water shadows), depth still writes
            // (OptiFine's shadow-pass beginWater keeps depthMask on).
            bindBlockAtlas(mc);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
            RenderDevice.enterManagedCode();
            try {
                worldRenderer.drawChunkLayer(BlockRenderLayer.TRANSLUCENT, camera.x, camera.y, camera.z);
            } finally {
                RenderDevice.exitManagedCode();
            }
            GlStateManager.disableBlend();
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

    private static void bindBlockAtlas(Minecraft mc) {
        mc.getTextureManager().bindTexture(net.minecraft.client.renderer.texture.TextureMap.LOCATION_BLOCKS_TEXTURE);
    }

    /**
     * Renders entities and block entities into the shadow map the way OptiFine does: fixed-function geometry with the
     * untransformed {@code shadow} program bound, the shadow matrices on the FF matrix stack, and the render origin at
     * the camera (matching the grid-snapped shadow model-view). Culling is a simple horizontal box of the shadow
     * frustum's half-extent — the OptiFine default (it has no per-entity shadow frustum either).
     */
    private void renderEntityShadows(Minecraft mc, Vector3d camera) {
        if (this.entityShadowProgram == null) {
            return;
        }
        World world = mc.world;
        Entity viewEntity = mc.getRenderViewEntity();
        if (world == null || viewEntity == null) {
            return;
        }
        float partialTicks = CapturedRenderingState.INSTANCE.getTickDelta();
        double cullRange = this.halfPlaneLength + 16.0;

        // FF matrix stack <- shadow matrices (entities/TESRs draw through gl_ModelViewProjectionMatrix).
        GlStateManager.matrixMode(GL_PROJECTION_MODE);
        GlStateManager.pushMatrix();
        loadMatrix(this.shadowProjection);
        GlStateManager.matrixMode(GL_MODELVIEW_MODE);
        GlStateManager.pushMatrix();
        loadMatrix(this.shadowModelView);

        try {
            // Anchor the render origin at the interpolated camera position (what the shadow model-view was built
            // around). Both dispatchers cache it; vanilla re-caches for the main pass right after this hook.
            mc.getRenderManager().cacheActiveRenderInfo(world, mc.fontRenderer, viewEntity, mc.pointedEntity,
                    mc.gameSettings, partialTicks);
            mc.getRenderManager().setRenderPosition(camera.x, camera.y, camera.z);
            TileEntityRendererDispatcher.instance.prepare(world, mc.getTextureManager(), mc.fontRenderer,
                    viewEntity, mc.objectMouseOver, partialTicks);

            this.shaderPackResourceRestorer.run();
            this.entityShadowProgram.getProgram().getProgram().bind();
            this.shaderPackResourceRestorer.run();
            this.entityShadowProgram.getUniforms().update();

            for (Entity entity : world.loadedEntityList) {
                if (entity.isDead
                        || Math.abs(entity.posX - camera.x) > cullRange
                        || Math.abs(entity.posZ - camera.z) > cullRange) {
                    continue;
                }
                mc.getRenderManager().renderEntityStatic(entity, partialTicks, false);
            }

            for (TileEntity tileEntity : world.loadedTileEntityList) {
                if (TileEntityRendererDispatcher.instance.getRenderer(tileEntity) == null) {
                    continue;
                }
                BlockPos pos = tileEntity.getPos();
                if (Math.abs(pos.getX() - camera.x) > cullRange || Math.abs(pos.getZ() - camera.z) > cullRange) {
                    continue;
                }
                TileEntityRendererDispatcher.instance.render(tileEntity, partialTicks, -1);
            }
        } finally {
            LWJGL.glUseProgram(0);
            GlStateManager.matrixMode(GL_PROJECTION_MODE);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL_MODELVIEW_MODE);
            GlStateManager.popMatrix();
            // Entity/TESR rendering rebinds textures and toggles state; restore what the terrain layers assume.
            GlStateManager.disableBlend();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
        }
    }

    private void loadMatrix(Matrix4f matrix) {
        matrix.get(this.matrixBuffer);
        GlStateManager.loadIdentity();
        GlStateManager.multMatrix(this.matrixBuffer);
    }

    /** Copies the shadow framebuffer's depth into {@code destination} (bound as this pass's FBO at call time). */
    private void copyDepthTo(DepthTexture destination) {
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + 31);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, destination.getTextureId());
        LWJGL.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, this.resolution, this.resolution);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
        // Unit 31 can belong to a custom texture or custom-image sampler on 32-unit drivers. Iris rebinds these
        // resources per program use; restore them before translucent shadow terrain/voxelization continues.
        this.shaderPackResourceRestorer.run();
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
        this.depthTextureNoTranslucents.destroy();
        LWJGL.glDeleteTextures(this.colorTexture0);
        LWJGL.glDeleteTextures(this.colorTexture1);
        if (this.entityShadowProgram != null) {
            this.entityShadowProgram.getProgram().destroy();
        }
    }
}
