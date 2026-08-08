package com.bdmajora.impetus.iris.pipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import com.bdmajora.impetus.engine.impl.gl.device.RenderDevice;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import com.bdmajora.impetus.impl.render.terrain.ImpetusWorldRenderer;
import com.bdmajora.impetus.iris.gl.framebuffer.IrisFramebuffer;
import com.bdmajora.impetus.iris.gl.program.DrawBuffers;
import com.bdmajora.impetus.iris.shaderpack.ProgramSource;
import com.bdmajora.impetus.iris.targets.DepthTexture;
import com.bdmajora.impetus.iris.uniforms.CapturedRenderingState;
import com.bdmajora.impetus.iris.uniforms.CelestialUniforms;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL12;
import com.bdmajora.impetus.lwjgl.GL13;
import com.bdmajora.impetus.lwjgl.GL14;
import com.bdmajora.impetus.lwjgl.GL30;
import com.bdmajora.impetus.lwjgl.GL33;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Map;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The shadow-map pass: renders the world a second time each frame from the sun's (or moon's) point of view, before
 * the main gbuffer pass samples the result. Follows Iris's {@code ShadowRenderer} geometry order exactly:
 * <ol>
 * <li>solid + cutout terrain (Impetus path, the pack's {@code shadow} program substituted);</li>
 * <li>entities, then block entities — fixed-function, with the untransformed {@code shadow} program bound and the
 * shadow matrices loaded on the FF matrix stack (OptiFine renders its shadow entities the same way);</li>
 * <li>the depth buffer is copied to {@code shadowtex1} (the translucent-excluded depth, same construction as
 * {@code depthtex1});</li>
 * <li>translucent terrain, unblended, writing color into {@code shadowcolor0}/{@code shadowcolor1} — colored/water
 * shadows — while its depth still lands in {@code shadowtex0}.</li>
 * </ol>
 * Hardware depth compare follows the pack's {@code const bool shadowHardwareFiltering[0/1]} declarations via sampler
 * objects bound by {@link IrisRenderingPipeline}.
 * <p>
 * The shadow camera is modern Iris's construction — an orthographic frustum of {@code shadowDistance} half-extent,
 * rotated by the shadow angle, snapped to {@code shadowIntervalSize} world intervals so texels don't swim.
 */
public class IrisShadowRenderer {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");
    public static final float DEFAULT_NEAR_PLANE = -100.05f;
    public static final float DEFAULT_FAR_PLANE = 156.0f;
    public static final float DEFAULT_INTERVAL_SIZE = 2.0f;

    // Fixed-function matrix modes (GlStateManager.matrixMode takes the raw GL enum).
    private static final int GL_MODELVIEW_MODE = 0x1700;
    private static final int GL_PROJECTION_MODE = 0x1701;
    private static final int GL_CULL_FACE = 0x0B44;
    private static final int GL_DEPTH_FUNC = 0x0B74;

    /** True while the shadow pass is drawing; consulted by the matrix/program/draw-buffer seams. */
    private static boolean shadowPassActive;

    private final int resolution;
    private final float halfPlaneLength;
    private final float nearPlane;
    private final float farPlane;
    private final float sunPathRotation;
    private final float intervalSize;
    private final Float shadowMapFov;

    /** shadowtex0: everything, translucents included. */
    private final DepthTexture depthTexture;
    /** shadowtex1: copied from shadowtex0 just before translucent shadow geometry draws. */
    private final DepthTexture depthTextureNoTranslucents;
    /** What the pack allows this pass to draw ({@code shadowTerrain}, {@code shadowEntities}, ...). */
    private final ShadowContentSettings content;
    /** {@code voxelDistance} — the safe-zone radius for {@code shadow.culling = reversed}; 0 when undeclared. */
    private final float voxelDistance;
    /** Whether the pack voxelizes in the shadow pass (geometry stage or custom images), per Iris's detection. */
    private final boolean packVoxelizes;
    /** {@code shadowDistance * shadowDistanceRenderMul} — the distance the shadow pass culls against. */
    private final float cullDistance;
    private final int colorTexture0;
    private final int colorTexture1;
    private final IrisFramebuffer framebuffer;
    private final boolean[] hardwareFiltering;
    private final boolean[] mipmapDepth;
    private final boolean[] nearestDepth;
    private final boolean separateHardwareSamplers;
    /** Both shadowcolor attachments, for the frame-start clear. */
    private static final int[] CLEAR_MASK = {0, 1};
    /** The pack's shadow DRAWBUFFERS mask (only shadowcolor0/1 exist), applied for the geometry draws. */
    private final int[] shadowDrawBuffers;
    private final Runnable shaderPackResourceRestorer;
    /**
     * The fixed-function flavor of the pack's {@code shadow} program, for entities/block entities (immediate-mode
     * geometry — the Impetus-format terrain shadow program cannot consume it). {@code null} if it failed to compile;
     * entity shadows are skipped then.
     */
    private final GbufferPrograms.Entry entityShadowProgram;

    private final FloatBuffer matrixBuffer =
            ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private int failureCount;
    /**
     * OFF unless {@code -Dimpetus.iris.shadowProbeFrame=<n>} names a shadow pass to sample. The probe reads back three
     * full 2048² buffers, so it is not something to leave running.
     */
    private static final int SHADOW_PROBE_FRAME = Integer.getInteger("impetus.iris.shadowProbeFrame", -1);
    private int shadowPassCounter;
    private boolean probeDone;
    private boolean failed;
    /** Monotonic frame tag for the dedicated shadow render-list graph updates (independent of the main list's). */
    private int shadowListFrame;
    private boolean destroyed;

    private final Matrix4f shadowModelView = new Matrix4f();
    private final Matrix4f shadowProjection = new Matrix4f();

    /**
     * @param shadowSource      the pack's {@code shadow} program source (for the fixed-function entity flavor).
     * @param samplerUnits      the standard sampler-unit table (with gbuffers-stage custom-texture overrides applied).
     * @param hardwareFiltering       per-texture {@code shadowHardwareFiltering} flags: [0] = shadowtex0, [1] = shadowtex1.
     * @param mipmapDepth             per-texture shadow depth mipmap flags.
     * @param nearestDepth            per-texture shadow depth nearest-filter flags.
     * @param separateHardwareSamplers whether hardware compare is exposed through {@code shadowtex*HW} aliases.
     */
    public IrisShadowRenderer(int resolution, float shadowDistance, float nearPlane, float farPlane,
                              float intervalSize, Float shadowMapFov, float sunPathRotation,
                              ProgramSource shadowSource, Map<String, Integer> samplerUnits,
                              Map<String, String> shaderDefines, boolean[] hardwareFiltering,
                              boolean[] mipmapDepth, boolean[] nearestDepth, boolean separateHardwareSamplers,
                              Runnable shaderPackResourceRestorer, ShadowContentSettings content,
                              float voxelDistance, float cullDistance, boolean packVoxelizes) {
        this.resolution = resolution;
        this.halfPlaneLength = shadowDistance;
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
        this.intervalSize = intervalSize;
        this.shadowMapFov = shadowMapFov;
        this.sunPathRotation = sunPathRotation;
        this.shaderPackResourceRestorer = shaderPackResourceRestorer;
        this.content = content;
        this.voxelDistance = voxelDistance;
        this.cullDistance = cullDistance;
        this.packVoxelizes = packVoxelizes;
        this.hardwareFiltering = hardwareFiltering.clone();
        this.mipmapDepth = mipmapDepth.clone();
        this.nearestDepth = nearestDepth.clone();
        this.separateHardwareSamplers = separateHardwareSamplers;

        this.depthTexture = createShadowDepthTexture(resolution, this.hardwareFiltering[0],
                this.mipmapDepth[0], this.nearestDepth[0], this.separateHardwareSamplers);
        this.depthTextureNoTranslucents = createShadowDepthTexture(resolution, this.hardwareFiltering[1],
                this.mipmapDepth[1], this.nearestDepth[1], this.separateHardwareSamplers);

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
                .map(source -> DrawBuffers.parseActive(source, shaderDefines))
                .map(buffers -> DrawBuffers.sanitize(buffers, 2))
                .orElse(new int[]{0});

        this.entityShadowProgram = GbufferPrograms.compile(shadowSource, shaderDefines, samplerUnits);
        if (this.entityShadowProgram == null) {
            LOGGER.warn("[Iris] Fixed-function shadow program failed to compile; entity shadows disabled");
        }

        LOGGER.info("[Iris] Shadow map ready: {}x{}, distance {}, near {}, far {}, interval {}, fov {}, hardware filtering [{}, {}], mipmaps [{}, {}], nearest [{}, {}], separate hardware samplers {}",
                resolution, resolution, shadowDistance, nearPlane, farPlane, intervalSize,
                shadowMapFov == null ? "ortho" : shadowMapFov, hardwareFiltering[0], hardwareFiltering[1],
                mipmapDepth[0], mipmapDepth[1], nearestDepth[0], nearestDepth[1], separateHardwareSamplers);
    }

    private static DepthTexture createShadowDepthTexture(int resolution, boolean hardwareFiltering,
                                                         boolean mipmap, boolean nearest,
                                                         boolean separateHardwareSamplers) {
        DepthTexture texture = new DepthTexture(resolution, resolution,
                GL14.GL_DEPTH_COMPONENT24, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture.getTextureId());
        if (hardwareFiltering && !separateHardwareSamplers) {
            LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
        }
        LWJGL.glTexParameteriv(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_RGBA,
                new int[]{GL11.GL_RED, GL11.GL_RED, GL11.GL_RED, GL11.GL_ONE});
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, shadowMinFilter(mipmap, nearest));
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, nearest ? GL11.GL_NEAREST : GL11.GL_LINEAR);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    private static int shadowMinFilter(boolean mipmap, boolean nearest) {
        if (mipmap) {
            return nearest ? GL11.GL_NEAREST_MIPMAP_NEAREST : GL11.GL_LINEAR_MIPMAP_LINEAR;
        }
        return nearest ? GL11.GL_NEAREST : GL11.GL_LINEAR;
    }

    /** The internal format of shadowcolor0/1, needed to bind them through the image API (shadowcolorimgN). */
    public static final int SHADOW_COLOR_INTERNAL_FORMAT = GL11.GL_RGBA8;

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
        ImpetusWorldRenderer worldRenderer = ImpetusWorldRenderer.instanceNullable();
        if (worldRenderer == null) {
            return;
        }

        computeMatrices();
        CapturedRenderingState.INSTANCE.setShadowModelView(this.shadowModelView);
        CapturedRenderingState.INSTANCE.setShadowProjection(this.shadowProjection);

        boolean cullWasEnabled = false;
        int previousDepthFunc = GL11.GL_LEQUAL;
        boolean restoreGlState = false;

        try {
            shadowPassActive = true;
            Minecraft mc = Minecraft.getMinecraft();
            this.framebuffer.bind();
            cullWasEnabled = LWJGL.glGetBoolean(GL_CULL_FACE);
            previousDepthFunc = LWJGL.glGetInteger(GL_DEPTH_FUNC);
            restoreGlState = true;
            LWJGL.glViewport(0, 0, this.resolution, this.resolution);
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.clearDepth(1.0D);
            GlStateManager.disableCull();
            // shadowcolor clears to white (no tint); GlStateManager keeps the vanilla clear-color cache coherent.
            this.framebuffer.drawBuffers(CLEAR_MASK);
            GlStateManager.clearColor(1.0f, 1.0f, 1.0f, 1.0f);
            LWJGL.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            this.framebuffer.drawBuffers(this.shadowDrawBuffers);

            GlStateManager.disableBlend();
            // The shadow program alpha-tests foliage against the block atlas; make sure it is what unit 0 holds
            // (this runs before vanilla's own "prepareterrain" atlas bind).
            bindBlockAtlas(mc);

            Vector3d camera = CapturedRenderingState.INSTANCE.getCameraPosition();

            // Iris `shadow.culling = reversed` parity: build the DEDICATED shadow render list containing every
            // built section within render distance — no camera frustum, no occlusion culling (isInShadowPass()
            // routes both this update and the draws below onto the shadow RenderListManager). Reusing the main
            // pass's culled lists made marginal/occluded sections (cave interiors especially) blink in and out of
            // the pack's voxelization, so the colored-lighting floodfill chased a different voxel field every
            // frame and never converged — visible as permanent strobing on every colored-lit surface.
            RenderDevice.enterManagedCode();
            try {
                // `shadow.culling`: `off` keeps every loaded section, otherwise the pass is bounded to a box of the
                // pack's own shadowDistance (Iris BoxCuller). Position-only, so the section set stays frame-stable.
                // The advanced/safe-zone frustums are derived from THIS frame's camera matrices, so the filter is
                // rebuilt every pass rather than cached.
                worldRenderer.setupTerrain(
                        new com.bdmajora.impetus.engine.impl.render.viewport.Viewport(
                                com.bdmajora.impetus.iris.pipeline.shadow.ShadowFrustums.create(
                                        this.content.getCulling(), this.cullDistance, this.voxelDistance,
                                        this.packVoxelizes,
                                        Minecraft.getMinecraft().gameSettings.renderDistanceChunks * 16,
                                        this.sunPathRotation),
                                new Vector3d(camera.x, camera.y, camera.z)),
                        ImpetusWorldRenderer.captureCameraState(mc.getRenderPartialTicks()),
                        ++this.shadowListFrame, false, false);
            } finally {
                RenderDevice.exitManagedCode();
            }

            // 1) Solid + cutout terrain (Iris order). Impetus requires draws inside its managed-device scope.
            if (this.content.shouldRenderTerrain()) {
                RenderDevice.enterManagedCode();
                try {
                    worldRenderer.drawChunkLayer(BlockRenderLayer.SOLID, camera.x, camera.y, camera.z);
                    worldRenderer.drawChunkLayer(BlockRenderLayer.CUTOUT_MIPPED, camera.x, camera.y, camera.z);
                    worldRenderer.drawChunkLayer(BlockRenderLayer.CUTOUT, camera.x, camera.y, camera.z);
                } finally {
                    RenderDevice.exitManagedCode();
                }
            }

            // 2) Entities + block entities, fixed-function under the shadow matrices.
            renderEntityShadows(mc, camera);

            // 3) shadowtex1 = depth without translucents (Iris copyPreTranslucentDepth).
            copyDepthTo(this.depthTextureNoTranslucents);

            // 4) Translucent terrain: writes the shadow tint into shadowcolor0/1 and its depth into shadowtex0.
            //
            // UNBLENDED, deliberately. OptiFine calls disableBlend() immediately before this draw
            // (ShadersRender.renderShadowMap) and Iris's ShadowRenderer never enables blending in the shadow pass at
            // all, so in both the shadow program's colour output REPLACES the buffer. Blending it instead mixes every
            // translucent texel back toward the white shadowcolor clear, using the fragment's alpha as the weight —
            // and Complementary's alpha there is not an opacity at all, it is the scene-aware light-shaft HEIGHT
            // (`color2.a = 0.25 + max0(positionYM * 0.05)`). The washed-out result then feeds the light shafts'
            // translucent-occluder branch as `pow2(shadowcolor1.rgb * 4.0)`, which turns a neutral 0.25 into ~16x
            // overbright over exactly the texels where a translucent surface shadows solid ground — measured at 8.4%
            // of the shadow map in this world. That is the sunlight that appeared to leak through terrain.
            if (this.content.shouldRenderTranslucent() && this.content.shouldRenderTerrain()) {
                bindBlockAtlas(mc);
                GlStateManager.disableBlend();
                RenderDevice.enterManagedCode();
                try {
                    worldRenderer.drawChunkLayer(BlockRenderLayer.TRANSLUCENT, camera.x, camera.y, camera.z);
                } finally {
                    RenderDevice.exitManagedCode();
                }
                GlStateManager.disableBlend();
            }
            generateMipmaps();
            probeShadowDepth();
            dumpShadowMapIfRequested();
        } catch (Throwable t) {
            // The very first frames can race renderer setup (no viewport/render lists yet) — only give up for good
            // after repeated failures.
            if (++this.failureCount >= 3) {
                this.failed = true;
                LOGGER.error("[Iris] Shadow pass failed repeatedly; disabling shadows for this pack", t);
            }
        } finally {
            if (restoreGlState) {
                if (cullWasEnabled) {
                    GlStateManager.enableCull();
                } else {
                    GlStateManager.disableCull();
                }
                GlStateManager.depthFunc(previousDepthFunc);
                GlStateManager.depthMask(true);
            }
            shadowPassActive = false;
        }
    }

    private static void bindBlockAtlas(Minecraft mc) {
        mc.getTextureManager().bindTexture(net.minecraft.client.renderer.texture.TextureMap.LOCATION_BLOCKS_TEXTURE);
    }

    private void generateMipmaps() {
        generateDepthMipmap(this.depthTexture, this.mipmapDepth[0], this.nearestDepth[0]);
        generateDepthMipmap(this.depthTextureNoTranslucents, this.mipmapDepth[1], this.nearestDepth[1]);
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
        this.shaderPackResourceRestorer.run();
    }

    private static void generateDepthMipmap(DepthTexture texture, boolean mipmap, boolean nearest) {
        if (!mipmap) {
            return;
        }
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + 31);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture.getTextureId());
        LWJGL.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, shadowMinFilter(true, nearest));
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
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
        if (!this.content.shouldRenderEntities() && !this.content.shouldRenderPlayer()
                && !this.content.shouldRenderAnyBlockEntities()) {
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

            // Entities and TESRs draw fixed-function, straight after Embeddium rendered shadow terrain from its own
            // VAO. Hand the vertex pipeline back clean, or a leftover generic attribute array wins over the aliased
            // client array (see IrisRenderingPipeline#resetVanillaVertexArrayState). This is also where the player
            // model's ModelRenderer display lists are first compiled — and a display list bakes its vertex data at
            // compile time, so a bad state here would flatten the first-person arm's texcoords for the whole session.
            IrisRenderingPipeline.resetVanillaVertexArrayState();

            if (this.content.shouldRenderEntities() || this.content.shouldRenderPlayer()) {
                for (Entity entity : world.loadedEntityList) {
                    if (entity.isDead
                            || Math.abs(entity.posX - camera.x) > cullRange
                            || Math.abs(entity.posZ - camera.z) > cullRange) {
                        continue;
                    }
                    // shadowPlayer and shadowEntities are independent switches, so the player is filtered separately.
                    boolean isPlayer = entity instanceof net.minecraft.entity.player.EntityPlayer;
                    if (isPlayer ? !this.content.shouldRenderPlayer() : !this.content.shouldRenderEntities()) {
                        continue;
                    }
                    mc.getRenderManager().renderEntityStatic(entity, partialTicks, false);
                }
            }

            if (this.content.shouldRenderAnyBlockEntities()) {
                boolean lightOnly = this.content.shouldRenderLightBlockEntitiesOnly();
                for (TileEntity tileEntity : world.loadedTileEntityList) {
                    if (TileEntityRendererDispatcher.instance.getRenderer(tileEntity) == null) {
                        continue;
                    }
                    BlockPos pos = tileEntity.getPos();
                    if (Math.abs(pos.getX() - camera.x) > cullRange || Math.abs(pos.getZ() - camera.z) > cullRange) {
                        continue;
                    }
                    // shadowLightBlockEntities without shadowBlockEntities: only emitters, so a pack doing voxel
                    // lighting still sees light sources without paying for every chest and sign.
                    if (lightOnly && tileEntity.getBlockType().getLightValue(
                            world.getBlockState(pos), world, pos) <= 0) {
                        continue;
                    }
                    TileEntityRendererDispatcher.instance.render(tileEntity, partialTicks, -1);
                }
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

    /**
     * One-shot diagnostic that runs on its own, once per pack load, a few seconds in. Logs two lines and never
     * repeats; {@code -Dimpetus.iris.shadowProbeFrame=<n>} only moves which pass it samples.
     * <p>
     * Complementary's light shafts read {@code shadowtex0} with {@code texelFetch}, and wherever that says "shadowed"
     * they cross-check {@code shadowtex1} (the pre-translucent depth) with {@code shadow2D}. If {@code shadowtex1}
     * claims the texel is lit, the pack concludes the occluder must be translucent glass or water and swaps the
     * density for {@code pow2(shadowcolor1.rgb * 4.0)} — and shadowcolor1 clears to white, making that path 16x
     * overbright. Over solid terrain that is precisely "sunlight leaking through the ground".
     * <p>
     * The number that settles it is {@code onlyIn0}: texels where shadowtex0 holds an occluder but shadowtex1 is
     * still at the clear value. That is the exact condition the pack's translucent branch tests, so it should be
     * near zero in a world with no glass or water overhead. A large value means the pre-translucent depth copy is
     * losing geometry, which is a bug here rather than in the pack.
     */
    private void probeShadowDepth() {
        // Retry rather than fire on a fixed count. A shader reload rebuilds this renderer and restarts the counter,
        // so a fixed trigger can land while the world is still loading — which reads a 100%-empty shadow map and
        // eyeBrightness (0,0), i.e. plausible-looking numbers that mean nothing.
        if (SHADOW_PROBE_FRAME < 0 || this.probeDone || this.shadowPassCounter++ < SHADOW_PROBE_FRAME) {
            return;
        }
        int depth0 = this.depthTexture.getTextureId();
        int depth1 = this.depthTextureNoTranslucents.getTextureId();
        try {
            float[] with = readDepth();
            if (fractionUntouched(with) > 0.99f) {
                // Nothing has been drawn into the shadow map yet; wait and look again shortly.
                this.shadowPassCounter = SHADOW_PROBE_FRAME - 60;
                return;
            }
            this.framebuffer.addDepthAttachment(depth1);
            float[] without = readDepth();
            this.framebuffer.addDepthAttachment(depth0);
            float[] rgba = readShadowColor1();
            logDepthComparison(with, without, rgba);
            logSceneAwareProbe(with, rgba);
            this.probeDone = true;
        } catch (Throwable t) {
            LOGGER.warn("[Iris] Shadow probe failed", t);
            this.probeDone = true;
        } finally {
            this.framebuffer.addDepthAttachment(depth0);
            this.framebuffer.bind();
            this.framebuffer.drawBuffers(this.shadowDrawBuffers);
        }
    }

    /**
     * Writes both shadow depth textures to PNGs when the player has just taken a screenshot, so the shadow map can be
     * read side by side with the frame it belongs to. {@code shadowtex0} is the one the light shafts sample.
     */
    private void dumpShadowMapIfRequested() {
        if (!com.bdmajora.impetus.iris.devtool.ShadowMapDump.consumeRequest()) {
            return;
        }
        int depth0 = this.depthTexture.getTextureId();
        int depth1 = this.depthTextureNoTranslucents.getTextureId();
        try {
            float[] depth = readDepth();
            logShadowLookupProbe(depth);
            com.bdmajora.impetus.iris.devtool.ShadowMapDump.writeDepth("shadowtex0", depth, this.resolution);
            this.framebuffer.addDepthAttachment(depth1);
            com.bdmajora.impetus.iris.devtool.ShadowMapDump.writeDepth("shadowtex1", readDepth(), this.resolution);
        } catch (Throwable t) {
            LOGGER.warn("[Iris] Shadow map dump failed", t);
        } finally {
            this.framebuffer.addDepthAttachment(depth0);
            this.framebuffer.bind();
            this.framebuffer.drawBuffers(this.shadowDrawBuffers);
        }
    }

    /**
     * Replays Complementary's {@code GetShadowPos} against the live matrices and the shadow map that was just
     * rendered, for points straight above the camera. This is the last unobserved link in the light-shaft path: the
     * shafts leak only if this lookup reports LIT for a sample that sits under solid rock.
     * <p>
     * Underground, the camera itself (h=0) and every point below the ceiling must come back {@code shadowed}. An
     * {@code OUTSIDE DISC} verdict is worse than a wrong depth — the pack skips the shadow test entirely there and
     * substitutes {@code localDensity = vec3(1.0)}, i.e. fully lit with no occlusion at all.
     */
    private void logShadowLookupProbe(float[] depth0) {
        float bias = 1.0f - 25.6f / this.halfPlaneLength;
        for (float h : new float[]{0.0f, 2.0f, 5.0f, 10.0f, 20.0f, 40.0f, 80.0f}) {
            org.joml.Vector4f p = new org.joml.Vector4f(0.0f, h, 0.0f, 1.0f);
            this.shadowModelView.transform(p);
            this.shadowProjection.transform(p);
            float len = (float) Math.sqrt(p.x * p.x + p.y * p.y);
            float distort = len * bias + (1.0f - bias);
            float sx = (p.x / distort) * 0.5f + 0.5f;
            float sy = (p.y / distort) * 0.5f + 0.5f;
            float sz = (p.z * 0.2f) * 0.5f + 0.5f;

            double ndc = Math.hypot(sx * 2.0 - 1.0, sy * 2.0 - 1.0);
            int tx = (int) (sx * this.resolution);
            int ty = (int) (sy * this.resolution);
            String verdict;
            String storedText = "-";
            if (ndc >= 1.0) {
                verdict = "OUTSIDE DISC -> pack forces localDensity=1.0 (FULLY LIT, no shadow test)";
            } else if (tx < 0 || ty < 0 || tx >= this.resolution || ty >= this.resolution) {
                verdict = "texel out of range " + tx + "," + ty;
            } else {
                float stored = depth0[ty * this.resolution + tx];
                storedText = Float.toString(stored);
                float sample = Math.max(0.0f, Math.min(1.0f, (stored - sz) * 65536.0f));
                verdict = sample > 0.5f ? "LIT" : "shadowed";
                if (stored >= 0.99999f) {
                    verdict += " (nothing rendered in this column)";
                }
            }
            LOGGER.info("[Iris] SHADOW LOOKUP h=+{}: uv=({}, {}) z={} ndc={} texel=({},{}) stored={} -> {}",
                    h, sx, sy, sz, ndc, tx, ty, storedText, verdict);
        }
    }

    private static float fractionUntouched(float[] depth) {
        int cleared = 0;
        for (float d : depth) {
            if (d >= 0.99999f) {
                cleared++;
            }
        }
        return (float) cleared / depth.length;
    }

    /**
     * shadowcolor1 as interleaved RGBA. The red channel feeds the light shafts' tint branch through
     * {@code pow2(rgb * 4.0)} (0.25 is neutral); the ALPHA channel carries the scene-aware light-shaft height,
     * {@code color2.a = 0.25 + max0(positionYM * 0.05)}, which is what drives {@code vlFactor}.
     */
    private float[] readShadowColor1() {
        int texels = this.resolution * this.resolution;
        ByteBuffer pixels = ByteBuffer.allocateDirect(texels * 4 * 4).order(ByteOrder.nativeOrder());
        this.framebuffer.bindAsReadBuffer();
        this.framebuffer.readBuffer(1);
        pixels.clear();
        LWJGL.glReadPixels(0, 0, this.resolution, this.resolution, GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
        float[] rgba = new float[texels * 4];
        pixels.asFloatBuffer().get(rgba);
        return rgba;
    }

    /**
     * Replays composite1's scene-aware light-shaft (SALS) probe exactly as the shader runs it, because its result is
     * what drives {@code vlFactor} — and {@code vlFactor} is the switch on the ONLY unshadowed term in the whole
     * raymarch. At {@code vlFactor == 0} the near-field covers the entire ray and every sample is shadow-tested; as
     * it rises, {@code qualityThreshold} collapses from ~188 blocks toward 100 and everything past that gets one
     * sample of {@code eyeBrightnessM} with no shadow test at all — distant fog that ignores geometry.
     * <p>
     * The shader's own loop: sample a 5x5 grid over the middle of the shadow map, keep texels whose depth is
     * {@code < 0.55}, recover {@code (a - 0.25) / 0.05} from shadowcolor1's alpha, and compare the average against a
     * threshold of 6.0. Above it {@code vlFactor} climbs toward 1, below it decays toward 0.
     */
    private void logSceneAwareProbe(float[] depth0, float[] rgba) {
        double heightSum = 0.0;
        int counted = 0;
        int considered = 0;
        for (double i = 0.25; i < 5.0; i++) {
            for (double h = 0.45; h < 5.0; h++) {
                double u = 0.3 + 0.4 * (1.0 / 5.0) * i;
                double v = 0.3 + 0.4 * (1.0 / 5.0) * h;
                int x = Math.min(this.resolution - 1, (int) (u * this.resolution));
                int y = Math.min(this.resolution - 1, (int) (v * this.resolution));
                int index = y * this.resolution + x;
                considered++;
                if (depth0[index] >= 0.55f) {
                    continue;
                }
                float alpha = rgba[index * 4 + 3];
                if (alpha > 0.0f) {
                    heightSum += Math.max(0.0f, alpha - 0.25f) / 0.05;
                    counted++;
                }
            }
        }
        double salsCheck = counted == 0 ? Double.NaN : heightSum / counted;
        LOGGER.info("[Iris] SHADOW PROBE SALS: sampled {}/{} grid points, salsCheck={} vs threshold 6.0 -> vlFactor {}",
                counted, considered, salsCheck,
                Double.isNaN(salsCheck) ? "DECAYS (no samples)" : (salsCheck > 6.0 ? "CLIMBS toward 1" : "decays to 0"));

        // With vlFactor pinned high, qualityThreshold collapses to 100 blocks and everything beyond it takes ONE
        // unshadowed sample of eyeBrightnessM. That is only harmless if eyeBrightnessM is genuinely low, which it
        // must be whenever the SALS says the camera is under cover — the two are meant to move together.
        org.joml.Vector2i eye = com.bdmajora.impetus.iris.uniforms.EyeBrightnessTracker.getEyeBrightness();
        Minecraft mc = Minecraft.getMinecraft();
        Entity cam = mc.getRenderViewEntity();
        int optifineWay = cam == null ? -1 : cam.getBrightnessForRender();
        int handRolled = -1;
        String eyePos = "?";
        if (cam != null && mc.world != null) {
            BlockPos p = new BlockPos(cam.posX, cam.posY + cam.getEyeHeight(), cam.posZ);
            eyePos = p.getX() + "," + p.getY() + "," + p.getZ();
            handRolled = mc.world.getCombinedLight(p, 0);
        }
        LOGGER.info("[Iris] SHADOW PROBE eyeBrightness=({},{}) of 240 -> eyeBrightnessM={} | eyePos={} "
                        + "getBrightnessForRender()=block {} sky {} | getCombinedLight()=block {} sky {}",
                eye.x, eye.y, com.bdmajora.impetus.iris.uniforms.CommonUniforms.getEyeBrightnessM(), eyePos,
                optifineWay & 0xFFFF, optifineWay >> 16, handRolled & 0xFFFF, handRolled >> 16);
    }

    private float[] readDepth() {
        int texels = this.resolution * this.resolution;
        ByteBuffer pixels = ByteBuffer.allocateDirect(texels * 4).order(ByteOrder.nativeOrder());
        this.framebuffer.bindAsReadBuffer();
        pixels.clear();
        LWJGL.glReadPixels(0, 0, this.resolution, this.resolution,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixels);
        FloatBuffer depths = pixels.asFloatBuffer();
        float[] out = new float[texels];
        depths.get(out);
        return out;
    }

    private void logDepthComparison(float[] with, float[] without, float[] rgba) {
        // 1.0 is the depth clear: nothing was ever drawn into that texel.
        final float clear = 0.99999f;
        int texels = with.length;
        int untouched0 = 0;
        int untouched1 = 0;
        int onlyIn0 = 0;
        int differ = 0;
        float min0 = Float.MAX_VALUE;
        float max0 = -Float.MAX_VALUE;
        double sum0 = 0.0;
        for (int i = 0; i < texels; i++) {
            float d0 = with[i];
            float d1 = without[i];
            min0 = Math.min(min0, d0);
            max0 = Math.max(max0, d0);
            sum0 += d0;
            boolean empty0 = d0 >= clear;
            boolean empty1 = d1 >= clear;
            if (empty0) {
                untouched0++;
            }
            if (empty1) {
                untouched1++;
            }
            if (!empty0 && empty1) {
                onlyIn0++;
            }
            if (Math.abs(d0 - d1) > 1.0e-6f) {
                differ++;
            }
        }
        LOGGER.info("[Iris] SHADOW PROBE {}x{} shadowtex0: min={} max={} mean={} untouched={}%",
                this.resolution, this.resolution, min0, max0, sum0 / texels, pct(untouched0, texels));
        LOGGER.info("[Iris] SHADOW PROBE shadowtex1: untouched={}%  differ-from-tex0={}%  "
                        + "onlyIn0(translucent-over-solid, where the pack's tint branch fires)={}%",
                pct(untouched1, texels), pct(differ, texels), pct(onlyIn0, texels));

        // What the tint branch actually consumes. It computes pow2(shadowcolor1.rgb * 4.0), so 0.25 is the neutral
        // value (-> 1.0) and anything near the white clear explodes (1.0 -> 16x). Restricted to the texels where the
        // branch fires, because everywhere else the value is irrelevant.
        double tintAll = 0.0;
        double tintFiring = 0.0;
        int firing = 0;
        int firingWhite = 0;
        for (int i = 0; i < texels; i++) {
            tintAll += rgba[i * 4];
            if (with[i] < clear && without[i] >= clear) {
                tintFiring += rgba[i * 4];
                firing++;
                if (rgba[i * 4] >= 0.95f) {
                    firingWhite++;
                }
            }
        }
        double meanFiring = firing == 0 ? 0.0 : tintFiring / firing;
        LOGGER.info("[Iris] SHADOW PROBE shadowcolor1.r: mean={} | over firing texels mean={} -> pow2(x*4)={} "
                        + "(neutral is 0.25 -> 1.0); still-white(>=0.95) there={}%",
                tintAll / texels, meanFiring, Math.pow(meanFiring * 4.0, 2.0),
                firing == 0 ? "n/a" : pct(firingWhite, firing));
    }

    private static String pct(int count, int total) {
        return String.format("%.3f", 100.0 * count / total);
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
        if (this.shadowMapFov != null) {
            // ShadowMatrices.createPerspectiveMatrix(fov).
            float yScale = (float) (1.0f / Math.tan(Math.toRadians(this.shadowMapFov) * 0.5f));
            this.shadowProjection.set(
                    yScale, 0.0f, 0.0f, 0.0f,
                    0.0f, yScale, 0.0f, 0.0f,
                    0.0f, 0.0f, (this.farPlane + this.nearPlane) / (this.nearPlane - this.farPlane), -1.0f,
                    0.0f, 0.0f, 2.0f * this.farPlane * this.nearPlane / (this.nearPlane - this.farPlane), 1.0f);
        } else {
            // ShadowMatrices.createOrthoMatrix(halfPlaneLength, nearPlane, farPlane).
            this.shadowProjection.identity().setOrtho(
                    -this.halfPlaneLength, this.halfPlaneLength,
                    -this.halfPlaneLength, this.halfPlaneLength,
                    this.nearPlane, this.farPlane);
        }

        // ---- createBaselineModelViewMatrix(target, shadowAngle, sunPathRotation) ----
        float shadowAngle = CelestialUniforms.getShadowAngle();
        float skyAngle;
        if (shadowAngle < 0.25f) {
            skyAngle = shadowAngle + 0.75f;
        } else {
            skyAngle = shadowAngle - 0.25f;
        }

        this.shadowModelView.identity()
                .rotateX((float) Math.toRadians(90.0f))
                .rotateZ((float) Math.toRadians(skyAngle * -360.0f))
                .rotateX((float) Math.toRadians(this.sunPathRotation));

        // ---- snapModelViewToGrid(target, intervalSize, cameraX, cameraY, cameraZ) ----
        Vector3d camera = CapturedRenderingState.INSTANCE.getCameraPosition();
        if (Math.abs(this.intervalSize) != 0.0f) {
            float halfIntervalSize = this.intervalSize / 2.0f;
            float offsetX = (float) camera.x % this.intervalSize - halfIntervalSize;
            float offsetY = (float) camera.y % this.intervalSize - halfIntervalSize;
            float offsetZ = (float) camera.z % this.intervalSize - halfIntervalSize;
            this.shadowModelView.translate(offsetX, offsetY, offsetZ);
        }
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
