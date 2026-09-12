package com.bdmajora.impetus.umbra.pipeline;

import com.bdmajora.impetus.umbra.gl.GlTextureUnits;
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
import com.bdmajora.impetus.umbra.gl.framebuffer.UmbraFramebuffer;
import com.bdmajora.impetus.umbra.gl.program.DrawBuffers;
import com.bdmajora.impetus.umbra.shaderpack.ProgramSource;
import com.bdmajora.impetus.umbra.targets.DepthTexture;
import com.bdmajora.impetus.umbra.uniforms.CapturedRenderingState;
import com.bdmajora.impetus.umbra.uniforms.CelestialUniforms;
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

// Second render of the world each frame from the sun/moon's POV, before the gbuffer pass samples it.
// Order matches Umbra's ShadowRenderer: solid+cutout terrain, then entities/block entities (fixed-function,
// shadow matrices on the FF stack), then depth copied to shadowtex1 (pre-translucent), then translucent
// terrain writes colored/water shadows into shadowcolor0/1 while landing depth in shadowtex0.
// Shadow camera is an ortho frustum of shadowDistance half-extent, rotated by shadow angle, snapped to
// shadowIntervalSize so texels don't swim.
public class UmbraShadowRenderer {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");
    public static final float DEFAULT_NEAR_PLANE = -100.05f;
    public static final float DEFAULT_FAR_PLANE = 156.0f;
    public static final float DEFAULT_INTERVAL_SIZE = 2.0f;

    // Fixed-function matrix modes (GlStateManager.matrixMode takes the raw GL enum).
    private static final int GL_MODELVIEW_MODE = 0x1700;
    private static final int GL_PROJECTION_MODE = 0x1701;
    private static final int GL_CULL_FACE = 0x0B44;
    private static final int GL_DEPTH_FUNC = 0x0B74;

    // True while the shadow pass is drawing. Consulted by the matrix, program and draw-buffer seams, which each
    // need to answer differently for the shadow pass than for the camera pass
    private static boolean shadowPassActive;

    private final int resolution;
    private final float halfPlaneLength;
    private final float nearPlane;
    private final float farPlane;
    private final float sunPathRotation;
    private final float intervalSize;
    private final Float shadowMapFov;

    // shadowtex0: every shadow caster, translucents included
    private final DepthTexture depthTexture;
    // shadowtex1: a copy of shadowtex0 taken just BEFORE translucent shadow geometry draws, so the difference
    // between the two is exactly "what is occluded only by glass and water" — which is how packs tint light shafts
    private final DepthTexture depthTextureNoTranslucents;
    // What the pack permits this pass to draw: shadowTerrain, shadowEntities, shadowBlockEntities and friends
    private final ShadowContentSettings content;
    // The pack's voxelDistance, i.e. the safe-zone radius used when shadow.culling = reversed; 0 when undeclared,
    // which degenerates that mode to plain advanced culling
    private final float voxelDistance;
    // Whether the pack voxelizes during the shadow pass, inferred the way Iris infers it — from the presence of a
    // geometry stage or custom images rather than from a declaration
    private final boolean packVoxelizes;
    // shadowDistance * shadowDistanceRenderMul — the distance the pass culls against, which is deliberately not the
    // same as the distance the projection covers
    private final float cullDistance;
    private final int colorTexture0;
    private final int colorTexture1;
    private final UmbraFramebuffer framebuffer;
    private final boolean[] hardwareFiltering;
    private final boolean[] mipmapDepth;
    private final boolean[] nearestDepth;
    private final boolean separateHardwareSamplers;
    // Both shadowcolor attachments, for the frame-start clear — only 0 and 1 exist on this version
    private static final int[] CLEAR_MASK = {0, 1};
    // Texture unit for one-off raw work: creating and configuring the shadow textures, generating mipmaps, copying
    // depth
    // Chosen above GlTextureUnits.CACHED_UNITS and clear of every unit the pipeline assigns, so this work can never
    // rewrite a slot GlStateManager is tracking
    // Always pair a selectScratch on it with a releaseScratch
    private static final int TEXTURE_SETUP_UNIT = 31;
    // The pack's shadow DRAWBUFFERS mask, applied for the geometry draws — sanitised down to shadowcolor0 and 1,
    // which are the only two that exist
    private final int[] shadowDrawBuffers;
    private final Runnable shaderPackResourceRestorer;
    // The FIXED-FUNCTION flavour of the pack's shadow program, for entities and block entities
    // A separate compile is necessary because those draw through immediate mode, and the Impetus-format terrain
    // shadow program cannot consume that geometry at all
    // Null when it failed to compile, in which case entity shadows are skipped rather than drawn wrong
    private final GbufferPrograms.Entry entityShadowProgram;

    // shadow_block's flavour, for the block-entity loop. Iris gives block entities their own shadow program rather
    // than reusing the entity one
    // When the pack ships neither, both ids resolve through the fallback chain onto the same `shadow` source and
    // this holds the very same compiled Entry object — which is what blockEntityProgramShared below exists to
    // detect, so teardown does not free it twice
    private final GbufferPrograms.Entry blockEntityShadowProgram;

    // Whether the two program fields hold the SAME object, which teardown checks before destroying either
    private final boolean blockEntityProgramShared;

    private final FloatBuffer matrixBuffer =
            ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private int failureCount;
    private boolean failed;
    // Monotonic frame tag for the shadow pass's own render-list graph updates, kept independent of the camera
    // pass's counter so neither can satisfy the other's dirty check
    private int shadowListFrame;
    private boolean destroyed;

    private final Matrix4f shadowModelView = new Matrix4f();
    private final Matrix4f shadowProjection = new Matrix4f();

    // shadowSource is the pack's shadow program source, compiled here into the fixed-function entity flavour
    // samplerUnits is the standard sampler-unit table with the gbuffers-stage custom-texture overrides already
    // applied, since the shadow pass belongs to that stage
    // hardwareFiltering, mipmapDepth and nearestDepth are all PER TEXTURE, index 0 being shadowtex0 and index 1
    // shadowtex1 — a pack routinely wants hardware compare on one and raw depth on the other
    // separateHardwareSamplers says whether hardware compare is exposed through the shadowtex*HW aliases instead
    public UmbraShadowRenderer(int resolution, float shadowDistance, float nearPlane, float farPlane,
                              float intervalSize, Float shadowMapFov, float sunPathRotation,
                              ProgramSource shadowSource,
                              ProgramSource shadowEntitiesSource, ProgramSource shadowBlockSource,
                              Map<String, Integer> samplerUnits,
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

        this.framebuffer = new UmbraFramebuffer();
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

        // shadow_entities / shadow_block, both falling back to plain `shadow`. The callers resolve them through the
        // ProgramSet fallback chain, so for a pack shipping only `shadow` these are the identical ProgramSource
        // object and the second compile is skipped outright — same program, same rendering as before this split.
        ProgramSource entitiesSource = shadowEntitiesSource != null ? shadowEntitiesSource : shadowSource;
        ProgramSource blockSource = shadowBlockSource != null ? shadowBlockSource : shadowSource;

        this.entityShadowProgram = GbufferPrograms.compile(entitiesSource, shaderDefines, samplerUnits);
        if (this.entityShadowProgram == null) {
            LOGGER.warn("[Umbra] Fixed-function shadow program failed to compile; entity shadows disabled");
        }
        this.blockEntityProgramShared = blockSource == entitiesSource;
        this.blockEntityShadowProgram = this.blockEntityProgramShared
                ? this.entityShadowProgram
                : GbufferPrograms.compile(blockSource, shaderDefines, samplerUnits);
    }

    private static DepthTexture createShadowDepthTexture(int resolution, boolean hardwareFiltering,
                                                         boolean mipmap, boolean nearest,
                                                         boolean separateHardwareSamplers) {
        DepthTexture texture = new DepthTexture(resolution, resolution,
                GL14.GL_DEPTH_COMPONENT24, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT);
        // Configure on a scratch unit. Binding raw on the selected unit (unit 0 at startup, holding whatever vanilla
        // last bound) would change the real binding without updating GlStateManager's record for that slot; the
        // trailing unbind then leaves the cache asserting a texture that is no longer there, and every later cached
        // bind of it no-ops. See GlTextureUnits.
        GlTextureUnits.selectScratch(TEXTURE_SETUP_UNIT);
        try {
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture.getTextureId());
            if (hardwareFiltering && !separateHardwareSamplers) {
                LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
            }
            LWJGL.glTexParameteriv(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_RGBA,
                    new int[]{GL11.GL_RED, GL11.GL_RED, GL11.GL_RED, GL11.GL_ONE});
            LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, shadowMinFilter(mipmap, nearest));
            LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                    nearest ? GL11.GL_NEAREST : GL11.GL_LINEAR);
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        } finally {
            GlTextureUnits.releaseScratch();
        }
        return texture;
    }

    // GL filter constant from the pack's mipmap and nearest flags
    private static int shadowMinFilter(boolean mipmap, boolean nearest) {
        if (mipmap) {
            return nearest ? GL11.GL_NEAREST_MIPMAP_NEAREST : GL11.GL_LINEAR_MIPMAP_LINEAR;
        }
        return nearest ? GL11.GL_NEAREST : GL11.GL_LINEAR;
    }

    // The internal format of shadowcolor0 and 1, which glBindImageTexture needs in order to expose them as
    // shadowcolorimgN
    public static final int SHADOW_COLOR_INTERNAL_FORMAT = GL11.GL_RGBA8;

    // One shadowcolor texture at the shadow resolution
    private static int createShadowColorTexture(int resolution) {
        int texture = LWJGL.glGenTextures();
        // Scratch unit, for the same reason as createShadowDepthTexture.
        GlTextureUnits.selectScratch(TEXTURE_SETUP_UNIT);
        try {
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            LWJGL.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, resolution, resolution, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        } finally {
            GlTextureUnits.releaseScratch();
        }
        return texture;
    }

    // Whether the shadow pass is currently rendering, checked by mixins that must skip work
    public static boolean isShadowPass() {
        return shadowPassActive;
    }

    // shadowtex0
    public int getDepthTextureId() {
        return this.depthTexture.getTextureId();
    }

    // shadowtex1
    public int getDepthTextureNoTranslucentsId() {
        return this.depthTextureNoTranslucents.getTextureId();
    }

    // shadowcolor0
    public int getColorTextureId() {
        return this.colorTexture0;
    }

    // shadowcolor1
    public int getColorTexture1Id() {
        return this.colorTexture1;
    }

    // Shadow map size from the pack
    public int getResolution() {
        return this.resolution;
    }

    // Renders the shadow map for this frame
    // Ordering is fixed: called right AFTER the camera matrices are captured, so the shadow angle and camera
    // position are current, and BEFORE any world geometry draws into the gbuffer, so the deferred chain can sample
    // a finished shadow map
    // Leaves the shadow framebuffer and viewport bound; the caller rebinds the gbuffer afterwards
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
            // Put the clear colour back immediately — leaking white out of this method is what caused the white
            // screen flash. The clear colour is GLOBAL and GlStateManager caches it, and vanilla only ever sets it
            // once per frame, at EntityRenderer.renderWorldPass "clear" (updateFogColor + clear(16640), line ~1326).
            // That runs BEFORE the "frustum" section this shadow pass hooks, so nothing restores it afterwards and
            // white survives to the top of the NEXT frame — where Minecraft.runGameLoop does
            // `GlStateManager.clear(16640)` against the DEFAULT framebuffer (the real screen) before binding
            // framebufferMc. So every frame cleared the window to white; it is normally hidden because
            // framebufferRender blits the world over it, and becomes visible as a flash whenever that blit is
            // delayed or skipped — e.g. while a screenshot stalls the game loop encoding the PNG.
            // The mixin sets this fog colour immediately before calling us, so it is exactly the value vanilla
            // had in the clear-colour slot a moment ago; alpha 0 matches vanilla's own clearColor(r, g, b, 0.0F).
            org.joml.Vector3f fog = CapturedRenderingState.INSTANCE.getFogColor();
            GlStateManager.clearColor(fog.x, fog.y, fog.z, 0.0f);

            GlStateManager.disableBlend();
            // The shadow program alpha-tests foliage against the block atlas; make sure it is what unit 0 holds
            // (this runs before vanilla's own "prepareterrain" atlas bind).
            bindBlockAtlas(mc);

            Vector3d camera = CapturedRenderingState.INSTANCE.getCameraPosition();

            // Umbra `shadow.culling = reversed` parity: build the DEDICATED shadow render list containing every
            // built section within render distance — no camera frustum, no occlusion culling (isInShadowPass()
            // routes both this update and the draws below onto the shadow RenderListManager). Reusing the main
            // pass's culled lists made marginal/occluded sections (cave interiors especially) blink in and out of
            // the pack's voxelization, so the colored-lighting floodfill chased a different voxel field every
            // frame and never converged — visible as permanent strobing on every colored-lit surface.
            RenderDevice.enterManagedCode();
            try {
                // `shadow.culling`: `off` keeps every loaded section, otherwise the pass is bounded to a box of the
                // pack's own shadowDistance (Umbra BoxCuller). Position-only, so the section set stays frame-stable.
                // The advanced/safe-zone frustums are derived from THIS frame's camera matrices, so the filter is
                // rebuilt every pass rather than cached.
                worldRenderer.setupTerrain(
                        new com.bdmajora.impetus.engine.impl.render.viewport.Viewport(
                                com.bdmajora.impetus.umbra.pipeline.shadow.ShadowFrustums.create(
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

            // 1) Solid + cutout terrain (Umbra order). Impetus requires draws inside its managed-device scope.
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

            // 3) shadowtex1 = depth without translucents (Umbra copyPreTranslucentDepth).
            copyDepthTo(this.depthTextureNoTranslucents);

            // 4) Translucent terrain: writes the shadow tint into shadowcolor0/1 and its depth into shadowtex0.
            //
            // UNBLENDED, deliberately. OptiFine calls disableBlend() immediately before this draw
            // (ShadersRender.renderShadowMap) and Umbra's ShadowRenderer never enables blending in the shadow pass at
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
        } catch (Throwable t) {
            // The very first frames can race renderer setup (no viewport/render lists yet) — only give up for good
            // after repeated failures.
            if (++this.failureCount >= 3) {
                this.failed = true;
                LOGGER.error("[Umbra] Shadow pass failed repeatedly; disabling shadows for this pack", t);
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

    // Terrain in the shadow pass still samples the atlas for cutouts
    private static void bindBlockAtlas(Minecraft mc) {
        mc.getTextureManager().bindTexture(net.minecraft.client.renderer.texture.TextureMap.LOCATION_BLOCKS_TEXTURE);
    }

    // Mips on the depth textures the pack asked for
    private void generateMipmaps() {
        generateDepthMipmap(this.depthTexture, this.mipmapDepth[0], this.nearestDepth[0]);
        generateDepthMipmap(this.depthTextureNoTranslucents, this.mipmapDepth[1], this.nearestDepth[1]);
        GlTextureUnits.resetToUnit0();
        this.shaderPackResourceRestorer.run();
    }

    // One depth texture's mip chain and filter
    private static void generateDepthMipmap(DepthTexture texture, boolean mipmap, boolean nearest) {
        if (!mipmap) {
            return;
        }
        GlTextureUnits.selectScratch(TEXTURE_SETUP_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture.getTextureId());
        LWJGL.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, shadowMinFilter(true, nearest));
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    // Binds one of the fixed-function shadow programs and refreshes its uniforms
    // The resource restorer runs on BOTH sides of the bind, because binding a program is what re-points the
    // shader-pack sampler units, and the entity renderers running in between will have rebound textures of their own
    // A null entry means that program failed to compile; falling back to the entity one is deliberate, since
    // leaving whatever was bound before would draw block entities under an unrelated program
    private void bindShadowGeometryProgram(GbufferPrograms.Entry program) {
        GbufferPrograms.Entry target = program != null ? program : this.entityShadowProgram;
        if (target == null) {
            return;
        }
        this.shaderPackResourceRestorer.run();
        target.getProgram().getProgram().bind();
        this.shaderPackResourceRestorer.run();
        target.getUniforms().update();
    }

    // Renders entities and block entities into the shadow map the way OptiFine does: fixed-function geometry with
    // the untransformed shadow program bound, the shadow matrices pushed onto the fixed-function matrix stack, and
    // the render origin at the camera so it matches the grid-snapped shadow model-view
    // Culling is a plain horizontal box of the shadow frustum's half-extent, which is OptiFine's default — it has
    // no per-entity shadow frustum either
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

            bindShadowGeometryProgram(this.entityShadowProgram);

            // Entities and TESRs draw fixed-function, straight after Embeddium rendered shadow terrain from its own
            // VAO. Hand the vertex pipeline back clean, or a leftover generic attribute array wins over the aliased
            // client array (see UmbraRenderingPipeline#resetVanillaVertexArrayState). This is also where the player
            // model's ModelRenderer display lists are first compiled — and a display list bakes its vertex data at
            // compile time, so a bad state here would flatten the first-person arm's texcoords for the whole session.
            UmbraRenderingPipeline.resetVanillaVertexArrayState();

            if (this.content.shouldRenderEntities() || this.content.shouldRenderPlayer()) {
                bindShadowGeometryProgram(this.entityShadowProgram);
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
                // Umbra draws block entities in the shadow pass under shadow_block, not the entity program. When the
                // pack ships neither this is the same object already bound above and the rebind is a no-op.
                bindShadowGeometryProgram(this.blockEntityShadowProgram);
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

    // Uploads a JOML matrix into the fixed-function stack
    private void loadMatrix(Matrix4f matrix) {
        matrix.get(this.matrixBuffer);
        GlStateManager.loadIdentity();
        GlStateManager.multMatrix(this.matrixBuffer);
    }

    // The distortion functions the installed packs use
    // A pack applies its distortion TWICE — per vertex in shadow.vsh while rasterising, and per pixel in the
    // composite lookup — so the two cancel, and anything assuming the wrong one manufactures a mismatch that exists
    // nowhere but in the assumption
    // Not hypothetical: this list previously had COMPLEMENTARY hardcoded with no indication that it had, and the
    // result was read as evidence of a ~19-block shadow offset in miniature and Body Camera, both of which actually
    // use LOLIP_P. The "offset" was the model disagreeing with itself
    private static final String[] SHADOW_DISTORTION_MODELS = {"COMPLEMENTARY", "LOLIP_P"};

    // Debug: share of the shadow map still at clear depth
    private static float fractionUntouched(float[] depth) {
        int cleared = 0;
        for (float d : depth) {
            if (d >= 0.99999f) {
                cleared++;
            }
        }
        return (float) cleared / depth.length;
    }

    // shadowcolor1 read back as interleaved RGBA
    // Both halves matter and they mean different things: the red channel feeds the light shafts' tint branch
    // through pow2(rgb * 4.0), where 0.25 is neutral, while the ALPHA channel carries the scene-aware light-shaft
    // height as 0.25 + max0(positionYM * 0.05), which is what actually drives vlFactor
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

    // Debug formatting
    private static String pct(int count, int total) {
        return String.format("%.3f", 100.0 * count / total);
    }

    // Copies the shadow framebuffer's depth into the destination texture
    // Requires the shadow FBO to be bound as this pass's framebuffer at call time, which is what makes the copy a
    // framebuffer read rather than a texture-to-texture blit
    private void copyDepthTo(DepthTexture destination) {
        GlTextureUnits.selectScratch(TEXTURE_SETUP_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, destination.getTextureId());
        LWJGL.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, this.resolution, this.resolution);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GlTextureUnits.resetToUnit0();
        // Unit 31 can belong to a custom texture or custom-image sampler on 32-unit drivers. Umbra rebinds these
        // resources per program use; restore them before translucent shadow terrain/voxelization continues.
        this.shaderPackResourceRestorer.run();
    }

    // The shadow camera, a verbatim port of Iris's ShadowMatrices — createModelViewMatrix, which is
    // createBaselineModelViewMatrix followed by snapModelViewToGrid, plus createOrthoMatrix
    // The port is direct because the operation order maps one-to-one: Mojang's PoseStack.multiply and mulPose
    // post-multiply exactly as JOML's translate and rotate* do, and Vector3f.XP/ZP.rotationDegrees(d) is
    // rotateX/rotateZ(toRadians(d))
    // The GRID SNAP is the part that matters and the part that was wrong before. Offsetting by the fractional
    // camera position and centring by half a cell is what keeps shadow-map texels from swimming as the camera
    // moves; the previous hand-rolled snap omitted the centring and had the sign backwards, which is what made the
    // shadows flicker
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

    // For the shadow matrix uniforms
    public Matrix4f getShadowModelView() {
        return this.shadowModelView;
    }

    // For the shadow matrix uniforms
    public Matrix4f getShadowProjection() {
        return this.shadowProjection;
    }

    // Frees the FBO and textures
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
        // Only when it is genuinely a second program: with no shadow_block in the pack this is the same object as
        // entityShadowProgram, and destroying it here would leave the line below freeing an already-deleted program.
        if (!this.blockEntityProgramShared && this.blockEntityShadowProgram != null) {
            this.blockEntityShadowProgram.getProgram().destroy();
        }
        if (this.entityShadowProgram != null) {
            this.entityShadowProgram.getProgram().destroy();
        }
    }
}
