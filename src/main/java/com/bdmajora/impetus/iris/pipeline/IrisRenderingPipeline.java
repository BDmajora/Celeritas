package com.bdmajora.impetus.iris.pipeline;

import com.bdmajora.impetus.iris.gl.GlTextureUnits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import com.bdmajora.impetus.iris.gl.framebuffer.IrisFramebuffer;
import com.bdmajora.impetus.iris.gl.program.DrawBuffers;
import com.bdmajora.impetus.iris.gl.blending.ProgramBlendState;
import com.bdmajora.impetus.iris.gl.program.GlProgram;
import com.bdmajora.impetus.iris.gl.program.IrisProgram;
import com.bdmajora.impetus.iris.gl.program.ProgramBuilder;
import com.bdmajora.impetus.iris.gl.program.ProgramUniforms;
import com.bdmajora.impetus.iris.gl.shader.GlShader;
import com.bdmajora.impetus.iris.gl.shader.ShaderType;
import com.bdmajora.impetus.iris.gl.texture.InternalTextureFormat;
import com.bdmajora.impetus.iris.gl.texture.PlainTexture;
import com.bdmajora.impetus.iris.gl.texture.StubShadowMap;
import com.bdmajora.impetus.iris.shaderpack.ProgramSource;
import com.bdmajora.impetus.iris.shaderpack.ShaderPack;
import com.bdmajora.impetus.iris.shaderpack.loading.ProgramArrayId;
import com.bdmajora.impetus.iris.shaderpack.loading.ProgramId;
import com.bdmajora.impetus.iris.shaderpack.preprocessor.GlslPreprocessor;
import com.bdmajora.impetus.iris.shaderpack.texture.CustomTextureTransformer;
import com.bdmajora.impetus.iris.shaderpack.texture.TextureStage;
import com.bdmajora.impetus.iris.targets.BufferFlipper;
import com.bdmajora.impetus.iris.targets.DepthTexture;
import com.bdmajora.impetus.iris.targets.IrisRenderTarget;
import com.bdmajora.impetus.iris.targets.IrisRenderTargets;
import com.bdmajora.impetus.iris.targets.NoiseTexture;
import com.bdmajora.impetus.iris.terrain.FullscreenTransformer;
import com.bdmajora.impetus.iris.terrain.ModernPackTransformer;
import com.bdmajora.impetus.iris.terrain.VanillaNameTransformer;
import com.bdmajora.impetus.iris.uniforms.CapturedRenderingState;
import com.bdmajora.impetus.iris.uniforms.CameraUniforms;
import com.bdmajora.impetus.iris.uniforms.CelestialUniforms;
import com.bdmajora.impetus.iris.uniforms.CommonUniforms;
import com.bdmajora.impetus.iris.uniforms.EyeBrightnessTracker;
import com.bdmajora.impetus.iris.uniforms.FrameUpdateNotifier;
import com.bdmajora.impetus.iris.uniforms.MatrixUniforms;
import com.bdmajora.impetus.iris.uniforms.SystemTimeUniforms;
import com.bdmajora.impetus.iris.features.FeatureFlags;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL12;
import com.bdmajora.impetus.lwjgl.GL13;
import com.bdmajora.impetus.lwjgl.GL14;
import com.bdmajora.impetus.lwjgl.GL15;
import com.bdmajora.impetus.lwjgl.GL20;
import com.bdmajora.impetus.lwjgl.GL30;
import com.bdmajora.impetus.lwjgl.GL33;
import com.bdmajora.impetus.mixin.core.terrain.ActiveRenderInfoAccessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The Iris-native frame pipeline: owns the gbuffer framebuffer that world rendering is redirected into, and the
 * composite/final full-screen chain that turns the gbuffer into the image on screen.
 * <p>
 * Frame flow (driven by the {@code EntityRenderer} mixin):
 * <ol>
 * <li>{@link #beginWorldRendering} (renderWorld HEAD) — binds the gbuffer FBO, so vanilla's own clear and all world
 * rendering (Impetus terrain via the pack's transformed {@code gbuffers_terrain}, everything else fixed-function)
 * lands in {@code colortex0..N} + {@code depthtex0};</li>
 * <li>{@link #captureRenderingState} (after {@code setupCameraTransform}) — copies the camera matrices vanilla itself
 * captured in {@code ActiveRenderInfo} into {@link CapturedRenderingState} for the uniform providers;</li>
 * <li>{@link #finishWorldRendering} (renderWorld RETURN) — runs each {@code composite}N pass ping-ponging the render
 * targets exactly like OptiFine's buffer flip, then {@code final} into Minecraft's framebuffer (or a plain blit of
 * {@code colortex0} when the pack has no {@code final}), then hands GL state back to vanilla.</li>
 * </ol>
 * Construction compiles every pass up front (via {@link FullscreenTransformer}); a pass that fails to compile is
 * skipped with an error log rather than aborting the pipeline. Must be created/used/destroyed on the render thread.
 */
public class IrisRenderingPipeline {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    // Texture units used by this fixed-unit 1.12 bridge. Fullscreen programs keep the existing colortexN -> unit N
    // mapping, while gbuffers programs use the OptiFine 1.12 aux slots for gaux1..4/colortex4..7.
    private static final int DEPTH_TEX_0_UNIT = 16;
    private static final int DEPTH_TEX_1_UNIT = 17;
    private static final int DEPTH_TEX_2_UNIT = 18;
    private static final int SHADOW_TEX_0_UNIT = 19;
    private static final int SHADOW_TEX_1_UNIT = 20;
    private static final int SHADOW_COLOR_0_UNIT = 21;
    private static final int SHADOW_COLOR_1_UNIT = 22;
    private static final int NOISE_TEX_UNIT = 23;
    private static final int SHADOW_TEX_0_HW_UNIT = 24;
    private static final int SHADOW_TEX_1_HW_UNIT = 25;
    // OptiFine 1.12 gbuffers-stage sampler units from Shaders.useProgram(): texture/lightmap/normals/specular use
    // 0..3, shadow maps use 4/5, depthtex0 uses 6, gaux1..4/colortex4..7 use 7..10, depthtex1 uses 12,
    // shadowcolor0/1 use 13/14, and noisetex uses 15.
    private static final int GBUFFER_DEPTH_TEX_0_UNIT = 6;
    private static final int GBUFFER_DEPTH_TEX_1_UNIT = 12;
    private static final int GBUFFER_SHADOW_TEX_0_UNIT = 4;
    private static final int GBUFFER_SHADOW_TEX_1_UNIT = 5;
    private static final int GBUFFER_SHADOW_COLOR_0_UNIT = 13;
    private static final int GBUFFER_SHADOW_COLOR_1_UNIT = 14;
    private static final int GBUFFER_NOISE_TEX_UNIT = 15;
    /**
     * {@code iris_overlay} — Iris's entity damage/hurt overlay sampler. 1.12.2 draws that flash as a separate
     * fixed-function pass rather than through a sampler, so there is no live overlay texture to expose; a fully
     * transparent 1x1 is bound instead, which is exactly the "no overlay" value Iris's own fallback provides.
     * Unit 11 is the one gap in OptiFine's 1.12 gbuffers layout (7..10 are gaux1..4, 12 is depthtex1).
     */
    private static final int GBUFFER_OVERLAY_UNIT = 11;
    /**
     * {@code iris_overlay} for the gbuffers stage lives on {@link #GBUFFER_OVERLAY_UNIT}; this is the fullscreen-stage
     * bind of the same 1x1 dummy. It sits ABOVE the sampleable range on purpose — no composite/deferred/final program
     * declares {@code iris_overlay} (it is an entity hurt-flash concept), so reserving a scarce low unit for it just
     * starved the pack's own samplers.
     */
    private static final int OVERLAY_TEX_UNIT = 34;
    /** Highest logical colortex index shader-pack gbuffer stages may address. FBO attachment points are packed. */
    private static final int GBUFFER_ATTACHMENT_LIMIT = IrisRenderTargets.MAX_COLOR_BUFFERS;
    /** High texture unit used transiently for depth-copy binds so no vanilla-tracked unit is disturbed. */
    private static final int DEPTH_COPY_SCRATCH_UNIT = 33;
    /**
     * Scratch unit for mipmap generation/reset, ABOVE every sampler allocation (colortex 0..15, depth 16..18,
     * shadow 19..25, noise 23, custom textures 26+, custom images 27..31). Mipmap ops bind textures raw; doing
     * that on unit 0 desyncs GlStateManager's 8-slot cache, after which bindColorSamplers "already bound" checks
     * skip the real rebind and a pass samples whatever mipmap target was bound last (BSL deferred1: colortex0
     * ended up reading the black colortex6 → whole screen black).
     */
    private static final int MIPMAP_SCRATCH_UNIT = 32;
    /**
     * Dedicated units for the pack's custom textures and image samplers, above every reserved sampler. The first custom
     * unit is shared only by transient depth-copy/capture helpers; custom textures are rebound after those scratch uses.
     */
    /**
     * Lowest unit the pack's custom textures/images may use. The {@code shadowtex*HW} units above it are only real
     * when the pack declared SEPARATE_HARDWARE_SAMPLERS; otherwise nothing samples them and they are handed to the
     * pack instead — Complementary needs seven custom sampler units (gaux4, colortex3, voxel, floodfill x2, wsr,
     * wsr_lod) and silently lost the last two when the budget stopped at 26.
     */
    private static final int CUSTOM_TEX_FIRST_UNIT = SHADOW_TEX_0_HW_UNIT;
    private static final int GL_MAX_TEXTURE_IMAGE_UNITS = 0x8872;
    private static final int GL_BACK_BUFFER = 0x0405;
    private static final int SHADER_PACK_RESOURCE_BARRIERS = 0x00000020 | 0x00000008 | 0x00002000;
    private static final int FULL_BRIGHT_LIGHTMAP = 0x00F000F0;
    /** Both halves of {@link #FULL_BRIGHT_LIGHTMAP} as the raw texcoord the lightmap texture matrix expects. */
    private static final float FULL_BRIGHT_LIGHTMAP_COORD = 240.0f;
    private static final float LIGHTMAP_TEXTURE_SCALE = 1.0f / 256.0f;
    private static final float LIGHTMAP_TEXTURE_OFFSET = 8.0f / 256.0f;
    /** Draw-buffer mask for fixed-function content with no pack program: plain color into colortex0 only. */
    private static final int[] FIXED_FUNCTION_MASK = {0};
    private static final String[] LEGACY_COLOR_TARGETS =
            {"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"};
    private static final Pattern MIPMAP_DIRECTIVE =
            Pattern.compile("const\\s+bool\\s+(\\w+?)MipmapEnabled\\s*=\\s*(true|false)\\s*;");
    /** Iris's {@code PackDirectives} defaults. The half-lives are in deciseconds (1/10 s = 2 ticks). */
    private static final float DEFAULT_CENTER_DEPTH_HALF_LIFE = 1.0f;
    private static final float DEFAULT_WETNESS_HALF_LIFE = 600.0f;
    private static final float DEFAULT_DRYNESS_HALF_LIFE = 200.0f;
    private static final float DEFAULT_EYE_BRIGHTNESS_HALF_LIFE = 10.0f;
    /** Sampler name -> logical colortex index, independent from the texture unit chosen for a stage. */
    private static final Map<String, Integer> COLOR_TARGETS_BY_NAME = new LinkedHashMap<>();
    /** Sampler name -> texture unit for deferred/composite/final programs. */
    private static final Map<String, Integer> FULLSCREEN_SAMPLER_UNITS = new LinkedHashMap<>();
    /** Sampler name -> texture unit for gbuffers/shadow-stage programs. */
    private static final Map<String, Integer> GBUFFER_SAMPLER_UNITS = new LinkedHashMap<>();
    private static final int[] GBUFFER_COLOR_TEXTURE_UNITS = new int[IrisRenderTargets.MAX_COLOR_BUFFERS];

    static {
        for (int i = 0; i < IrisRenderTargets.MAX_COLOR_BUFFERS; i++) {
            COLOR_TARGETS_BY_NAME.put("colortex" + i, i);
            FULLSCREEN_SAMPLER_UNITS.put("colortex" + i, i);
            if (i < LEGACY_COLOR_TARGETS.length) {
                COLOR_TARGETS_BY_NAME.put(LEGACY_COLOR_TARGETS[i], i);
                FULLSCREEN_SAMPLER_UNITS.put(LEGACY_COLOR_TARGETS[i], i);
            }
            GBUFFER_COLOR_TEXTURE_UNITS[i] = -1;
        }
        // OptiFine 1.12 gbuffers stage: gaux1..4 live on texture units 7..10. Iris exposes the matching colortex
        // aliases too, so gbuffers colortex4..7 share those same aux units.
        for (int i = 4; i <= 7; i++) {
            GBUFFER_COLOR_TEXTURE_UNITS[i] = i + 3;
        }
        for (int i = 4; i <= 7; i++) {
            GBUFFER_SAMPLER_UNITS.put("colortex" + i, GBUFFER_COLOR_TEXTURE_UNITS[i]);
            if (i < LEGACY_COLOR_TARGETS.length) {
                GBUFFER_SAMPLER_UNITS.put(LEGACY_COLOR_TARGETS[i], GBUFFER_COLOR_TEXTURE_UNITS[i]);
            }
        }
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "depthtex0", DEPTH_TEX_0_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "gdepthtex", DEPTH_TEX_0_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "dhDepthTex", DEPTH_TEX_0_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "dhDepthTex0", DEPTH_TEX_0_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "depthtex1", DEPTH_TEX_1_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "dhDepthTex1", DEPTH_TEX_1_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "depthtex2", DEPTH_TEX_2_UNIT);
        // Shadow samplers are parked on unused units so a shadow-reading pack samples nothing instead of colortex0
        // (sampler uniforms default to unit 0). The shadow pass itself is a later phase.
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "shadowcolor1", SHADOW_COLOR_1_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "shadowcolor0", SHADOW_COLOR_0_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "shadowcolor", SHADOW_COLOR_0_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "shadowtex0", SHADOW_TEX_0_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "shadowtex0DH", SHADOW_TEX_0_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "shadow", SHADOW_TEX_0_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "watershadow", SHADOW_TEX_0_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "shadowtex1", SHADOW_TEX_1_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "shadowtex1DH", SHADOW_TEX_1_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "shadowtex0HW", SHADOW_TEX_0_HW_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "shadowtex1HW", SHADOW_TEX_1_HW_UNIT);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "noisetex", NOISE_TEX_UNIT);
        // OptiFine gbuffer-stage PBR samplers. During fullscreen passes these units are also colortex2/3, so the same
        // mapping remains correct for packs that leave the aliases active in shared include code.
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "normals", 2);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "texNorm", 2);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "specular", 3);
        putSharedSampler(FULLSCREEN_SAMPLER_UNITS, "texSpecular", 3);

        putGbufferSampler("depthtex0", GBUFFER_DEPTH_TEX_0_UNIT);
        putGbufferSampler("gdepthtex", GBUFFER_DEPTH_TEX_0_UNIT);
        putGbufferSampler("dhDepthTex", GBUFFER_DEPTH_TEX_0_UNIT);
        putGbufferSampler("dhDepthTex0", GBUFFER_DEPTH_TEX_0_UNIT);
        putGbufferSampler("depthtex1", GBUFFER_DEPTH_TEX_1_UNIT);
        putGbufferSampler("dhDepthTex1", GBUFFER_DEPTH_TEX_1_UNIT);
        putGbufferSampler("shadowcolor1", GBUFFER_SHADOW_COLOR_1_UNIT);
        putGbufferSampler("shadowcolor0", GBUFFER_SHADOW_COLOR_0_UNIT);
        putGbufferSampler("shadowcolor", GBUFFER_SHADOW_COLOR_0_UNIT);
        putGbufferSampler("iris_overlay", GBUFFER_OVERLAY_UNIT);
        putGbufferSampler("shadowtex0", GBUFFER_SHADOW_TEX_0_UNIT);
        putGbufferSampler("shadowtex0DH", GBUFFER_SHADOW_TEX_0_UNIT);
        putGbufferSampler("shadow", GBUFFER_SHADOW_TEX_0_UNIT);
        putGbufferSampler("watershadow", GBUFFER_SHADOW_TEX_0_UNIT);
        putGbufferSampler("shadowtex1", GBUFFER_SHADOW_TEX_1_UNIT);
        putGbufferSampler("shadowtex1DH", GBUFFER_SHADOW_TEX_1_UNIT);
        putGbufferSampler("noisetex", GBUFFER_NOISE_TEX_UNIT);
    }

    private static void putSharedSampler(Map<String, Integer> fullscreen, String name, int unit) {
        fullscreen.put(name, unit);
        GBUFFER_SAMPLER_UNITS.put(name, unit);
    }

    private static void putGbufferSampler(String name, int unit) {
        GBUFFER_SAMPLER_UNITS.put(name, unit);
    }

    /**
     * One entry of a numbered pass family: a full-screen draw ({@code program != null}) into its own framebuffer, or
     * the final pass (drawn to the screen, {@code framebuffer == null}), or a compute-only entry
     * ({@code program == null}) for a family index the pack only supplies {@code .csh} files for.
     * <p>
     * {@link #computes} are the program's compute stages ({@code <name>.csh} and the letter-suffixed
     * {@code <name>_a.csh} .. {@code _z.csh}). Iris dispatches them <em>before</em> the pass's own draw, under the same
     * flip state — Photon's {@code deferred4_a.csh} writes the skylight SH that {@code deferred4} then reads.
     */
    private static final class FullscreenPass {
        final String name;
        final IrisProgram program;
        final ProgramUniforms uniforms;
        final IrisFramebuffer framebuffer;
        /** Per color buffer, the texture to bind as {@code colortexN} when this pass runs (0 = target not in use). */
        final int[] colorSamplers;
        /** Logical render targets in shader output-slot order, used for per-target blend directives. */
        final int[] drawBuffers;
        final ProgramBlendState blendState;
        final BitSet flipsBefore;
        final BitSet flipsAfter;
        final BitSet mipmappedBuffers;
        /** Compute stages dispatched before this pass draws; never null, usually empty. */
        final List<ComputePass> computes;
        /**
         * Viewport for this pass, taken from its draw buffers' size ({@code size.buffer.colortexN}). Zero means
         * "the current render size", which is the case for every pass of a pack that declares no buffer sizes.
         */
        int viewportWidth;
        int viewportHeight;

        FullscreenPass(String name, IrisProgram program, ProgramUniforms uniforms, IrisFramebuffer framebuffer,
                       int[] colorSamplers, int[] drawBuffers, ProgramBlendState blendState,
                       BitSet flipsBefore, BitSet flipsAfter, BitSet mipmappedBuffers, List<ComputePass> computes) {
            this.name = name;
            this.program = program;
            this.uniforms = uniforms;
            this.framebuffer = framebuffer;
            this.colorSamplers = colorSamplers;
            this.drawBuffers = drawBuffers;
            this.blendState = blendState;
            this.flipsBefore = flipsBefore;
            this.flipsAfter = flipsAfter;
            this.mipmappedBuffers = mipmappedBuffers;
            this.computes = computes;
        }

        /**
         * Iris's {@code CompositeRenderer.ComputeOnlyPass}: a family slot with computes but no vertex/fragment pair.
         * It draws nothing, so it flips nothing, but it still needs the flip state and colortex snapshot of its
         * position in the chain — that is what its computes read and image-write.
         */
        static FullscreenPass computeOnly(String name, int[] colorSamplers, BitSet flips, List<ComputePass> computes) {
            return new FullscreenPass(name, null, null, null, colorSamplers, DrawBuffers.DEFAULT.clone(), null,
                    flips, (BitSet) flips.clone(), new BitSet(), computes);
        }
    }

    private final IrisRenderTargets renderTargets;
    private final FullscreenQuadRenderer quadRenderer;
    private final NoiseTexture noiseTexture;
    /** Fallback PBR inputs for the gbuffer stage: flat up-normal and black specular (OptiFine's defaults). */
    private final PlainTexture defaultNormals;
    private final PlainTexture defaultSpecular;
    /** The "no overlay active" stand-in bound on the {@code iris_overlay} units. */
    private final PlainTexture noOverlayTexture;
    /** "Always lit" 1×1 shadow map on the shadowtex units until the real shadow pass exists. */
    private final StubShadowMap stubShadowMap;
    /**
     * Whether the pack DECLARED {@code SEPARATE_HARDWARE_SAMPLERS}, not whether this port could provide it — Iris
     * reads {@code programSet.getPack().hasFeature(...)} for exactly this
     * ({@code IrisRenderingPipeline.java:222}). Keying it off {@code isUsable()} made it permanently true, which
     * suppressed the {@code GL_TEXTURE_COMPARE_MODE} that {@code IrisShadowRenderer.createShadowDepthTexture}
     * otherwise sets on the shadow depth textures, leaving hardware depth compare supplied only by per-unit sampler
     * objects. Any path that rebinds a shadow unit without also restoring its sampler object then leaves a
     * {@code sampler2DShadow} reading a texture whose compare mode is NONE — undefined, and "fully lit" on NVIDIA.
     */
    private boolean separateHardwareSamplers;
    private final boolean[] shadowHardwareFiltering = new boolean[2];
    private final boolean[] shadowMipmap = new boolean[2];
    private final boolean[] shadowNearest = new boolean[2];
    private final int shadowLinearHwSampler;
    private final int shadowNearestHwSampler;
    private final int shadowMippedLinearHwSampler;
    private final int shadowMippedNearestHwSampler;
    /**
     * ONE baked frame schedule, exactly like Iris: the composite chain flips some colortex buffers an odd number of
     * times per frame (e.g. Complementary's {@code colortex2} TAA history, written once by composite6), and instead
     * of alternating schedules per frame, the frame ENDS by copying each such buffer's alt side back to main
     * ({@link SwapPass}, Iris {@code FinalPassRenderer.SwapPass}). Every frame therefore starts from the canonical
     * state "main = latest", and all baked FBOs/sampler snapshots stay valid forever. Cross-frame temporal
     * accumulation (TAA) works because a history pass reads main (last frame's copy-back) and writes alt.
     */
    private IrisFramebuffer gbufferFramebuffer;
    private IrisFramebuffer translucentGbufferFramebuffer;
    /**
     * The {@code setup} family: compute-only, dispatched <em>once</em> after the pipeline is built rather than every
     * frame (Iris runs it when the dimension changes). Photon uses it to seed its LPV volumes.
     */
    private final List<FullscreenPass> setupPasses = new ArrayList<>();
    private boolean setupDispatched;
    /**
     * {@code prepareBeforeShadow}: run the prepare family before the shadow map instead of after it. Packs whose
     * shadow pass samples what prepare produces need this ordering.
     */
    private boolean prepareBeforeShadow;
    /**
     * {@code allowConcurrentCompute}: skip the full memory barrier between consecutive compute dispatches. Only safe
     * when the pack states its dispatches are independent.
     */
    private boolean allowConcurrentCompute;
    /** {@code rain.depth} — whether rain/snow writes into the depth buffer (Iris shouldWriteRainAndSnowToDepthBuffer). */
    private boolean rainDepth;
    /** {@code beacon.beam.depth} — whether the beacon beam writes into the depth buffer. */
    private boolean beaconBeamDepth;
    /** {@code frustum.culling} / {@code occlusion.culling} — vanilla culling switches; both default on. */
    private boolean frustumCulling = true;
    private boolean occlusionCulling = true;
    /** {@code skipAllRendering} — draw no world geometry at all, leaving only the composite chain. */
    private boolean skipAllRendering;
    /** {@code separateEntityDraws} — entities render in their own pass after the deferred chain. */
    private boolean separateEntityDraws;
    /** {@code particles.ordering} = mixed | after | before, relative to the deferred chain. */
    private String particleOrdering = "mixed";
    /**
     * {@code backFace.solid|cutout|cutoutMipped|translucent} — per-terrain-layer back-face culling. Vanilla culls
     * every layer; a pack that shades both sides of a face (or reads geometry from the light's side) asks for a
     * layer's back faces to be kept. Indexed by {@link net.minecraft.util.BlockRenderLayer#ordinal()}.
     */
    private final boolean[] backFaceCulling = {true, true, true, true};
    /** The {@code begin} family: runs at the very start of world rendering, before anything is drawn. */
    private final List<FullscreenPass> beginPasses = new ArrayList<>();
    /**
     * The {@code prepare} family: runs after the shadow map, before the gbuffers. Photon's {@code prepare} builds the
     * cloud shadow map and cumulus coverage map into colortex8, which its terrain lighting and sky both read.
     */
    private final List<FullscreenPass> preparePasses = new ArrayList<>();
    private final List<FullscreenPass> deferredPasses = new ArrayList<>();
    private final List<FullscreenPass> passes = new ArrayList<>();
    private IrisFramebuffer blitSourceFramebuffer;
    private final List<SwapPass> swapPasses = new ArrayList<>();
    /** Per-render-target clear directives parsed from the pack sources. Iris defaults every colortex to clear=true. */
    private final boolean[] colorBufferClears = new boolean[IrisRenderTargets.MAX_COLOR_BUFFERS];
    /** Explicit colortexNClearColor values; null means Iris's default color for that buffer. */
    private final float[][] colorBufferClearColors = new float[IrisRenderTargets.MAX_COLOR_BUFFERS][];
    /** Regular per-frame clears: only buffers whose colortexNClear directive is true, both main and alt sides. */
    private final List<ClearPass> clearPasses = new ArrayList<>();
    /** First-frame / resized-storage clears: every materialized buffer, both main and alt sides. */
    private final List<ClearPass> fullClearPasses = new ArrayList<>();
    private boolean fullClearRequired = true;
    /**
     * Fullscreen programs + their uniforms, compiled once and cached by name. A name mapping to {@code null} means
     * that program failed to compile. Owns the GL programs — they are destroyed here, not per-pass.
     */
    private final java.util.Map<String, IrisProgram> compiledPrograms = new java.util.HashMap<>();
    private final java.util.Map<String, ProgramUniforms> compiledUniforms = new java.util.HashMap<>();
    /** The pack's fixed-function gbuffer programs (sky/entities/particles/weather/clouds/hand), phase-switched. */
    private final GbufferPrograms gbufferPrograms;
    /** The pack's custom textures ({@code texture.*}/{@code customTexture.*} directives) and their unit overrides. */
    private final CustomTextureManager customTextureManager;
    /** The pack's writable custom images ({@code image.*} directives — Complementary's colored-lighting volumes). */
    private final CustomImageManager customImageManager;
    /**
     * The {@code shadowcomp} family, dispatched as a block right after the shadow map renders. Compute stages only —
     * a shadowcomp <em>raster</em> stage would draw into shadowcolor0/1 (which {@link IrisShadowRenderer} does own),
     * but nothing schedules those yet; Iris does it in {@code ShadowCompositeRenderer}.
     */
    private final List<FullscreenPass> shadowCompPasses = new ArrayList<>();
    /**
     * Render targets a program writes through the image API ({@code colorimgN}), mapped to the image unit they are
     * bound on — Iris's {@code IrisImages.addRenderTargetImages}. Photon's {@code deferred4_a.csh} stores its skylight
     * spherical harmonics with {@code imageStore(colorimg4, ...)}; with no such binding the compute writes nowhere and
     * every surface loses its sky ambient. Like Iris, the bound texture follows the pass's buffer flips, so it is
     * always the same side of the ping-pong pair that {@code colortexN} reads.
     */
    private final Map<Integer, Integer> renderTargetImageUnits = new LinkedHashMap<>();
    /**
     * {@code shadowcolorimg0}/{@code shadowcolorimg1} — the shadow colour attachments exposed through the image API
     * (Iris {@code IrisImages.addShadowColorImages}). Index 0/1 → image unit, empty when the pack references neither
     * or there is no shadow pass to own the textures.
     */
    private final Map<Integer, Integer> shadowColorImageUnits = new LinkedHashMap<>();
    /** Active shader macro environment: built-in MC/IRIS macros plus the pack's resolved option values. */
    private final Map<String, String> shaderDefines;

    /** One compute dispatch: the linked program, its uniforms, and the work-group counts. */
    private static final class ComputePass {
        final String name;
        final GlProgram program;
        final ProgramUniforms uniforms;
        final int groupsX;
        final int groupsY;
        final int groupsZ;
        /** Screen-relative dispatch ({@code const vec2 workGroupsRender}); NaN = fixed dispatch. */
        final float renderScaleX;
        final float renderScaleY;
        final int localSizeX;
        final int localSizeY;
        /** Indirect dispatch ({@code indirect.<pass>} directive): GL buffer id, or -1 for direct dispatch. */
        final int indirectBuffer;
        final long indirectOffset;

        ComputePass(String name, GlProgram program, ProgramUniforms uniforms, int groupsX, int groupsY, int groupsZ,
                    float renderScaleX, float renderScaleY, int localSizeX, int localSizeY,
                    int indirectBuffer, long indirectOffset) {
            this.name = name;
            this.program = program;
            this.uniforms = uniforms;
            this.groupsX = groupsX;
            this.groupsY = groupsY;
            this.groupsZ = groupsZ;
            this.renderScaleX = renderScaleX;
            this.renderScaleY = renderScaleY;
            this.localSizeX = localSizeX;
            this.localSizeY = localSizeY;
            this.indirectBuffer = indirectBuffer;
            this.indirectOffset = indirectOffset;
        }
    }
    /**
     * The gbuffers/shadow-stage sampler overrides of the <em>active</em> pipeline, consulted by the static
     * {@link #assignSamplerUnitsToBoundProgram} that the Impetus terrain/shadow overrides call (their program
     * objects are Impetus's, built lazily outside this class). Set on construction, cleared on destroy.
     */
    private static volatile Map<String, CustomTextureManager.Override> activeGbufferSamplerOverrides =
            java.util.Collections.emptyMap();
    private static volatile Map<String, Integer> activeGbufferSamplerUnits = GBUFFER_SAMPLER_UNITS;
    /**
     * Color targets written (flipped) by at least one earlier pass while the composite/deferred chain is being built.
     * Iris parity: a custom-texture override on a colortex deactivates once a pass has written that buffer — later
     * passes must read the chain's content, not the custom texture. Only mutated during construction.
     */
    private final TreeSet<Integer> flippedAtLeastOnce = new TreeSet<>();
    /** Every color index attached to the gbuffer FBOs: the union of all gbuffer-stage DRAWBUFFERS masks, sorted. */
    private final int[] gbufferAttachments;
    /** Logical colortex index -> physical gbuffer attachment point. */
    private final Map<Integer, Integer> gbufferAttachmentPoints = new LinkedHashMap<>();
    /** The gbuffer FBO the world is currently rendering into (switches after the deferred chain runs). */
    private IrisFramebuffer currentGbuffer;
    /** Scratch read FBO used to snapshot a gbuffer color target before a program reads and writes it. */
    private IrisFramebuffer gbufferFeedbackCopyFramebuffer;
    /** Iris-style colortex flip snapshot used by opaque gbuffers programs, before the deferred chain runs. */
    private BitSet preTranslucentGbufferSamplerFlips = new BitSet();
    /** Iris-style colortex flip snapshot used by translucent gbuffers programs, after the deferred chain runs. */
    private BitSet translucentGbufferSamplerFlips = new BitSet();
    /** The flip snapshot currently used to bind colortex4..7 for gbuffers programs. */
    private BitSet activeGbufferSamplerFlips = new BitSet();
    /** The shadow-map pass, or {@code null} when the pack declares no {@code shadow} program. */
    private final IrisShadowRenderer shadowRenderer;
    private final FrameUpdateNotifier frameUpdateNotifier = new FrameUpdateNotifier();

    /** centerDepthSmooth producer + its pack-configurable smoothing half-life (seconds). */
    private final CenterDepthSampler centerDepthSampler = new CenterDepthSampler();
    private float centerDepthHalfLife = DEFAULT_CENTER_DEPTH_HALF_LIFE;

    /**
     * The pack-wide scalar {@code const} directives, with Iris's defaults ({@code PackDirectives}'s constructor).
     * The three half-lives are in <em>deciseconds</em>, the unit Iris's {@code SmoothedFloat} takes.
     */
    private int noiseTextureResolution = NoiseTexture.DEFAULT_RESOLUTION;
    private float ambientOcclusionLevel = 1.0f;
    private float wetnessHalfLife = DEFAULT_WETNESS_HALF_LIFE;
    private float drynessHalfLife = DEFAULT_DRYNESS_HALF_LIFE;
    private float eyeBrightnessHalfLife = DEFAULT_EYE_BRIGHTNESS_HALF_LIFE;

    /** Optional final-presentation wide-gamut conversion (user-configured, defaults to sRGB = off). */
    private final ColorSpaceConverter colorSpaceConverter = new ColorSpaceConverter();

    /** Pack-declared shader storage buffers; {@code null} until construction. */
    private com.bdmajora.impetus.iris.gl.buffer.ShaderStorageBufferHolder shaderStorageBuffers;

    /** {@code indirect.<pass>} directives: pass name → {bufferObject index, byte offset}. */
    private Map<String, long[]> indirectDispatchPointers = java.util.Collections.emptyMap();

    /** GL43 dispatch-indirect binding target (kept as a literal to avoid a hard generated-constant dependency). */
    private static final int GL_DISPATCH_INDIRECT_BUFFER = 0x90EE;

    /**
     * End-of-frame alt->main copy-back for a buffer the chain left odd-flipped (Iris FinalPassRenderer.SwapPass).
     * {@code from} is a read framebuffer over the buffer's ALT texture; the copy target is its MAIN texture.
     */
    private static final class SwapPass {
        final IrisFramebuffer from;
        final int targetTexture;
        final int index;
        /** The target's own dimensions — a size.buffer-sized buffer must not be copied at the screen size. */
        final int width;
        final int height;

        SwapPass(int index, IrisFramebuffer from, int targetTexture, int width, int height) {
            this.index = index;
            this.from = from;
            this.targetTexture = targetTexture;
            this.width = width;
            this.height = height;
        }
    }

    private static final class ClearPass {
        final IrisFramebuffer framebuffer;
        final float[] color;
        /** Clear viewport; 0 means the current render size. Explicitly-sized buffers need their own. */
        final int width;
        final int height;

        ClearPass(IrisFramebuffer framebuffer, float[] color, int width, int height) {
            this.framebuffer = framebuffer;
            this.color = color;
            this.width = width;
            this.height = height;
        }
    }

    private boolean worldRenderingActive;
    private boolean destroyed;
    /**
     * True when the pack's fullscreen shaders are modern (#version 130+). Such passes position the quad with the
     * fixed-function {@code ftransform()}/{@code gl_TextureMatrix[0]}, so the composite chain must run with identity
     * model-view/projection/texture matrices (see {@link #runPass}).
     */
    private boolean modernPack;
    /**
     * Frames left to probe {@code glGetError} around each composite pass (0 = off). Pinpoints which pass/step raises
     * the {@code 1282 Invalid operation} Minecraft's "Post render" check reports. Counts down over the opening frames.
     */
    private int glErrorProbeFrames =
            Math.max(0, Integer.getInteger("impetus.iris.glErrorProbeFrames", 3));

    public IrisRenderingPipeline(ShaderPack pack) {
        Minecraft mc = Minecraft.getMinecraft();
        this.renderTargets = new IrisRenderTargets(mc.displayWidth, mc.displayHeight);
        this.shaderDefines = pack.getEnvironmentDefines();
        // The GPU identity macros need a live GL context, so they are added here rather than baked into the pack's
        // environment defines. Without them every hardware-workaround gate in every pack silently took its
        // "unknown vendor" branch — Clarity's `#if ... defined MC_GL_VENDOR_NVIDIA` left `immut` expanding to nothing
        // instead of `const`.
        com.bdmajora.impetus.iris.gl.shader.ShaderMacros.withGpuIdentity(this.shaderDefines,
                LWJGL.glGetString(GL11.GL_VENDOR), LWJGL.glGetString(GL11.GL_RENDERER));
        this.separateHardwareSamplers = pack.hasFeature(FeatureFlags.SEPARATE_HARDWARE_SAMPLERS);
        java.util.Arrays.fill(this.colorBufferClears, true);
        CameraUniforms.attach(this.frameUpdateNotifier);

        boolean initialized = false;
        try {
            this.quadRenderer = new FullscreenQuadRenderer();
            this.defaultNormals = new PlainTexture(127, 127, 255, 255);
            this.defaultSpecular = new PlainTexture(0, 0, 0, 0);
            // Transparent, so a pack doing `mix(color, overlay.rgb, overlay.a)` gets its colour back unchanged.
            this.noOverlayTexture = new PlainTexture(0, 0, 0, 0);
            this.stubShadowMap = new StubShadowMap();
            this.shadowLinearHwSampler = createShadowHardwareSampler(true, false);
            this.shadowNearestHwSampler = createShadowHardwareSampler(false, false);
            this.shadowMippedLinearHwSampler = createShadowHardwareSampler(true, true);
            this.shadowMippedNearestHwSampler = createShadowHardwareSampler(false, true);

            List<ProgramSource> fullscreenSources = collectFullscreenSources(pack);
            // colortexNFormat / clear directives may live in ANY program stage, not just fullscreen passes.
            // Iris/OptiFine text-scan every program in the pack; Sildur declares its HDR formats
            // (R11F_G11F_B10F, RGB16F) inside gbuffers_textured.fsh, so a fullscreen-only scan leaves every
            // target at RGBA8 and its HDR lighting/PCSS/bloom/TAA buffers clamp. Scan all sources.
            applyPackFormatDirectives(collectAllProgramSources(pack));
            // After the directive scan: `const int noiseTextureResolution` sizes this, and a pack that samples
            // noisetex at an assumed resolution gets the wrong spatial frequency if we guess 256.
            this.noiseTexture = new NoiseTexture(this.noiseTextureResolution);
            // size.buffer.colortexN must land before anything materialises a target: Photon's sky map is authored
            // against a 192x108 colortex4 and indexes it by absolute texel, so at full resolution its light/ambient
            // column lands mid-screen instead of at the edge.
            pack.getProperties().getBufferSizes().forEach((index, size) -> {
                if (index < 0 || index >= IrisRenderTargets.MAX_COLOR_BUFFERS) {
                    return;
                }
                boolean[] relative = pack.getProperties().getBufferSizeRelative(index);
                this.renderTargets.setColorSize(index, size[0], size[1], relative);
                LOGGER.info("[Iris] Render target colortex{} sized {}{} x {}{}", index,
                        size[0], relative[0] ? " (relative)" : "",
                        size[1], relative[1] ? " (relative)" : "");
            });
            materializeSampledTargets(fullscreenSources);

            // Publish the pack's block.properties mapping for the chunk meshers (null keeps raw 1.12.2 IDs). Done
            // here rather than at pack parse because registry resolution needs the game fully initialized.
            com.bdmajora.impetus.iris.material.WorldRenderingSettings.setBlockStateIds(
                    com.bdmajora.impetus.iris.material.BlockMaterialMapping.createBlockStateIdTable(pack.getIdMap()));
            com.bdmajora.impetus.iris.material.WorldRenderingSettings.setBlockRenderLayers(
                    com.bdmajora.impetus.iris.material.BlockMaterialMapping.createBlockRenderLayerTable(pack.getIdMap()));
            com.bdmajora.impetus.iris.material.WorldRenderingSettings.setItemIds(pack.getIdMap().getItemIdMap());
            com.bdmajora.impetus.iris.material.WorldRenderingSettings.setEntityIds(pack.getIdMap().getEntityIdMap());
            com.bdmajora.impetus.iris.material.WorldRenderingSettings.setVoxelRenderDistanceChunks(
                    mc.gameSettings.renderDistanceChunks);

            // Custom images/textures must exist before any program compiles: sampler-unit assignment consults
            // the overrides (image uniforms are plain glUniform1i assignments like samplers).
            int firstCustomUnit = this.separateHardwareSamplers
                    ? SHADOW_TEX_1_HW_UNIT + 1 : CUSTOM_TEX_FIRST_UNIT;
            this.customTextureManager = new CustomTextureManager(pack, samplerUnitsByStage(), COLOR_TARGETS_BY_NAME,
                    firstCustomUnit, maxProgrammableTextureUnit());
            this.customImageManager = new CustomImageManager(pack.getProperties().getIrisCustomImages(),
                    this.customTextureManager.getNextAvailableUnit(), maxProgrammableTextureUnit(),
                    mc.displayWidth, mc.displayHeight);
            allocateRenderTargetImageUnits(collectAllProgramSources(pack));
            activeGbufferSamplerUnits = GBUFFER_SAMPLER_UNITS;
            activeGbufferSamplerOverrides = mergedStageOverrides(TextureStage.GBUFFERS_AND_SHADOW);

            // Custom uniforms must exist before any program compiles, so every compile path can register them.
            com.bdmajora.impetus.iris.uniforms.custom.ActiveCustomUniforms.set(
                    pack.getProperties().getCustomUniforms().build());

            // Pack-declared SSBOs (bufferObject.<index> directives), zero-filled and bound at fixed indices.
            this.shaderStorageBuffers = new com.bdmajora.impetus.iris.gl.buffer.ShaderStorageBufferHolder(
                    com.bdmajora.impetus.iris.gl.buffer.ShaderStorageBufferHolder.parseDefinitions(
                            pack.getProperties().getRaw()),
                    mc.displayWidth, mc.displayHeight);
            this.indirectDispatchPointers = parseIndirectPointers(pack.getProperties().getRaw());

            this.prepareBeforeShadow = pack.getProperties().getPrepareBeforeShadow().orElse(Boolean.FALSE);
            com.bdmajora.impetus.iris.material.WorldRenderingSettings.setDynamicHandLight(
                    pack.getProperties().getDynamicHandLight().orElse(Boolean.TRUE));
            com.bdmajora.impetus.iris.material.WorldRenderingSettings.setSeparateAo(
                    pack.getProperties().getSeparateAo().orElse(Boolean.FALSE));
            com.bdmajora.impetus.iris.material.WorldRenderingSettings.setOldLighting(
                    pack.getProperties().getOldLighting().orElse(Boolean.TRUE));
            com.bdmajora.impetus.iris.material.WorldRenderingSettings.setOldHandLight(
                    pack.getProperties().getOldHandLight().orElse(Boolean.TRUE));
            com.bdmajora.impetus.iris.material.WorldRenderingSettings.setVoxelizeLightBlocks(
                    pack.getProperties().getVoxelizeLightBlocks().orElse(Boolean.FALSE));
            this.allowConcurrentCompute = pack.getProperties().getAllowConcurrentCompute().orElse(Boolean.FALSE);
            this.rainDepth = pack.getProperties().getRainDepth().orElse(Boolean.FALSE);
            this.beaconBeamDepth = pack.getProperties().getBeaconBeamDepth().orElse(Boolean.FALSE);
            this.frustumCulling = pack.getProperties().getFrustumCulling().orElse(Boolean.TRUE);
            this.occlusionCulling = pack.getProperties().getOcclusionCulling().orElse(Boolean.TRUE);
            this.skipAllRendering = pack.getProperties().getSkipAllRendering().orElse(Boolean.FALSE);
            this.separateEntityDraws = pack.getProperties().getSeparateEntityDraws().orElse(Boolean.FALSE);
            // Iris's resolution order: an explicit directive wins, otherwise a pack with a deferred chain that is
            // not using separate entity draws gets `after` (so particles are not lit by the deferred pass), and
            // everything else `mixed`.
            this.particleOrdering = pack.getProperties().getParticleOrdering().orElseGet(() -> {
                boolean hasDeferred = pack.getProgramSet().get(ProgramArrayId.Deferred, 0).isPresent();
                return hasDeferred && !this.separateEntityDraws ? "after" : "mixed";
            });
            LOGGER.info("[Iris] Particle ordering: {}", this.particleOrdering);
            // BlockRenderLayer order on 1.12.2 is SOLID, CUTOUT_MIPPED, CUTOUT, TRANSLUCENT.
            String[] backFaceKeys = {"solid", "cutoutMipped", "cutout", "translucent"};
            for (int i = 0; i < backFaceKeys.length; i++) {
                this.backFaceCulling[i] = pack.getProperties()
                        .getBackFaceCulling(backFaceKeys[i]).orElse(Boolean.TRUE);
                if (!this.backFaceCulling[i]) {
                    LOGGER.info("[Iris] backFace.{} = false; back faces kept for that layer", backFaceKeys[i]);
                }
            }
            this.gbufferPrograms = new GbufferPrograms(pack, GBUFFER_SAMPLER_UNITS, gbufferSamplerOverrideUnits());
            this.gbufferAttachments = computeGbufferAttachments(pack, terrainDrawBuffers(pack));
            for (int i = 0; i < this.gbufferAttachments.length; i++) {
                this.gbufferAttachmentPoints.put(this.gbufferAttachments[i], i);
                // A gbuffer FBO mixing attachment sizes renders into the intersection of them, which would silently
                // shrink the world pass. Packs size reflection/bloom buffers this way, never gbuffer outputs, so this
                // is a pack bug (or a bad parse) worth surfacing rather than a case to support.
                if (this.renderTargets.hasCustomSize(this.gbufferAttachments[i])) {
                    LOGGER.warn("[Iris] colortex{} declares its own size but is also a gbuffer output; the world pass "
                                    + "would be clipped to {}x{}", this.gbufferAttachments[i],
                            this.renderTargets.getWidth(this.gbufferAttachments[i]),
                            this.renderTargets.getHeight(this.gbufferAttachments[i]));
                }
            }
            this.shadowRenderer = createShadowRenderer(pack);
            allocateShadowColorImageUnits(collectAllProgramSources(pack));
            BufferFlipper flipper = this.renderTargets.getBufferFlipper();

            // Bake the single schedule from the reset flip state, then record which buffers the chain leaves
            // odd-flipped: those get an end-of-frame alt->main copy-back (Iris's SwapPass), so the next frame's
            // baked FBOs and sampler snapshots are valid again without any per-frame parity.
            buildSchedule(pack, flipper);
            buildSwapPasses(flipper);
            buildClearPasses();
            logRenderTargetSchedule(flipper);

            LOGGER.info("[Iris] Rendering pipeline ready: {} begin + {} prepare + {} deferred + {} composite/final pass(es){}, {} swap(s), gbuffer {}x{}",
                    this.beginPasses.size(), this.preparePasses.size(),
                    this.deferredPasses.size(), this.passes.size(),
                    this.blitSourceFramebuffer != null ? " + colortex0 blit" : "",
                    this.swapPasses.size(),
                    this.renderTargets.getWidth(), this.renderTargets.getHeight());

            // Pipeline setup creates and checks Iris FBOs as a side effect. Give Minecraft's main target back before
            // vanilla reaches its next post-render GL check.
            LWJGL.glUseProgram(0);
            bindMainRenderTarget(mc);
            restoreMainDrawReadBuffers(mc);
            restoreTextureUnits();
            initialized = true;
        } finally {
            if (!initialized) {
                destroy();
            }
        }
    }

    private static int maxProgrammableTextureUnit() {
        return Math.max(CUSTOM_TEX_FIRST_UNIT - 1, LWJGL.glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS) - 1);
    }

    private static int createShadowHardwareSampler(boolean linear, boolean mipmapped) {
        int sampler = LWJGL.glGenSamplers();
        LWJGL.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, linear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
        LWJGL.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, mipmapped
                ? (linear ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_NEAREST_MIPMAP_NEAREST)
                : (linear ? GL11.GL_LINEAR : GL11.GL_NEAREST));
        LWJGL.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        LWJGL.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        LWJGL.glSamplerParameteri(sampler, GL14.GL_TEXTURE_COMPARE_MODE, GL14.GL_COMPARE_R_TO_TEXTURE);
        return sampler;
    }

    // ------------------------------------------------------------------ construction

    /** The numbered families that render full-screen quads into the color targets, in the order they run. */
    private static final ProgramArrayId[] FULLSCREEN_FAMILIES = {
            ProgramArrayId.Begin, ProgramArrayId.Prepare, ProgramArrayId.Deferred, ProgramArrayId.Composite
    };
    // NB: Setup is deliberately absent — it is compute-only, so it contributes no sampled targets or draw buffers.

    private static void collectFamily(ShaderPack pack, ProgramArrayId id, List<ProgramSource> sources) {
        for (int i = 0; i < id.getNumPrograms(); i++) {
            pack.getProgramSet().get(id, i).ifPresent(sources::add);
        }
    }

    private List<ProgramSource> collectFullscreenSources(ShaderPack pack) {
        List<ProgramSource> sources = new ArrayList<>();
        for (ProgramArrayId id : FULLSCREEN_FAMILIES) {
            collectFamily(pack, id, sources);
        }
        pack.getProgramSet().get(ProgramId.Final).ifPresent(sources::add);
        return sources;
    }

    /**
     * Every present program source in the pack (all gbuffer families + shadow + the numbered deferred/composite
     * arrays + final), for directive scanning only. Buffer-format and clear directives ({@code const int
     * colortexNFormat}, {@code const bool colortexNClear}, ...) are conventionally placed inside a {@code /*...*}{@code /}
     * block in whichever file the pack author chose — Iris/OptiFine text-scan the whole pack, so we must too.
     * Duplicates are harmless: the directive scan is idempotent (re-setting a target to the same format is a no-op).
     */
    private List<ProgramSource> collectAllProgramSources(ShaderPack pack) {
        List<ProgramSource> sources = new ArrayList<>();
        for (ProgramId id : ProgramId.values()) {
            pack.getProgramSet().get(id).ifPresent(sources::add);
        }
        for (ProgramArrayId id : ProgramArrayId.values()) {
            collectFamily(pack, id, sources);
        }
        return sources;
    }

    private static final Pattern RENDER_TARGET_IMAGE = Pattern.compile("\\bcolorimg(\\d{1,2})\\b");

    /**
     * Reserves an image unit for every {@code colorimgN} the pack references, above the units the {@code image.<name>}
     * directives took. Iris assigns these per program from its {@code ProgramImages} builder; with this pipeline's
     * fixed-unit architecture one global unit per referenced target is equivalent and much simpler, because the
     * binding is re-established per pass anyway ({@link #bindRenderTargetImages}).
     */
    private void allocateRenderTargetImageUnits(List<ProgramSource> sources) {
        TreeSet<Integer> referenced = new TreeSet<>();
        for (ProgramSource source : sources) {
            collectRenderTargetImages(source.getVertexSource().orElse(null), referenced);
            collectRenderTargetImages(source.getFragmentSource().orElse(null), referenced);
            collectRenderTargetImages(source.getGeometrySource().orElse(null), referenced);
            for (String compute : source.getComputeSources()) {
                collectRenderTargetImages(compute, referenced);
            }
        }
        if (referenced.isEmpty()) {
            return;
        }

        int unit = this.customImageManager.getNextAvailableImageUnit();
        int limit = this.customImageManager.getHardwareImageUnits();
        for (Integer index : referenced) {
            if (unit >= limit) {
                LOGGER.error("[Iris] Out of image units for colorimg{} (max {}); ignoring it", index, limit);
                continue;
            }
            // Iris createIfUnsure()s the target: a buffer nothing samples but a compute writes still has to exist.
            this.renderTargets.getOrCreate(index);
            this.renderTargetImageUnits.put(index, unit);
            LOGGER.info("[Iris] Render target image colorimg{} on image unit {}", index, unit);
            unit++;
        }
    }

    /**
     * Reserves image units for {@code shadowcolorimg0/1}. Runs after the render-target images so the two share one
     * ascending allocation, and only when a shadow renderer exists to own the textures.
     */
    private void allocateShadowColorImageUnits(List<ProgramSource> sources) {
        if (this.shadowRenderer == null) {
            return;
        }
        TreeSet<Integer> referenced = new TreeSet<>();
        Pattern pattern = Pattern.compile("\\bshadowcolorimg([01])\\b");
        for (ProgramSource source : sources) {
            for (String stage : new String[]{source.getVertexSource().orElse(null),
                    source.getFragmentSource().orElse(null), source.getGeometrySource().orElse(null)}) {
                if (stage == null) {
                    continue;
                }
                Matcher matcher = pattern.matcher(stage);
                while (matcher.find()) {
                    referenced.add(Integer.parseInt(matcher.group(1)));
                }
            }
            for (String compute : source.getComputeSources()) {
                if (compute == null) {
                    continue;
                }
                Matcher matcher = pattern.matcher(compute);
                while (matcher.find()) {
                    referenced.add(Integer.parseInt(matcher.group(1)));
                }
            }
        }
        if (referenced.isEmpty()) {
            return;
        }

        int unit = this.customImageManager.getNextAvailableImageUnit() + this.renderTargetImageUnits.size();
        int limit = this.customImageManager.getHardwareImageUnits();
        for (Integer index : referenced) {
            if (unit >= limit) {
                LOGGER.error("[Iris] Out of image units for shadowcolorimg{} (max {}); ignoring it", index, limit);
                continue;
            }
            this.shadowColorImageUnits.put(index, unit);
            LOGGER.info("[Iris] Shadow color image shadowcolorimg{} on image unit {}", index, unit);
            unit++;
        }
    }

    private static void collectRenderTargetImages(String source, TreeSet<Integer> out) {
        if (source == null) {
            return;
        }
        Matcher matcher = RENDER_TARGET_IMAGE.matcher(source);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index < IrisRenderTargets.MAX_COLOR_BUFFERS) {
                out.add(index);
            }
        }
    }

    /**
     * Applies the pack's render-target format directives ({@code const int colortex0Format = RGBA16;}, including the
     * legacy {@code gcolorFormat}-style names), the OptiFine way: declared as consts anywhere in the composite/
     * deferred/final sources. Must run before any target is materialized. HDR packs depend on this — with plain RGBA8
     * their tonemapping input is clamped and highlights blow out.
     */
    /**
     * OptiFine's pre-{@code colortexNFormat} way of upgrading gaux4/colortex7, written as a <em>comment</em>:
     * {@code /* GAUX4FORMAT:RGB32F *}{@code /}. Only the three formats OptiFine accepted are honoured, matching Iris's
     * {@code PackRenderTargetDirectives}; anything else is a pack error and keeps the default.
     */
    private void applyLegacyGaux4Format(List<ProgramSource> sources) {
        Pattern directive = Pattern.compile("/\\*\\s*GAUX4FORMAT\\s*:\\s*(\\w+)\\s*\\*/");
        for (ProgramSource source : sources) {
            for (String stage : activeDirectiveStages(source)) {
                Matcher matcher = directive.matcher(stage);
                while (matcher.find()) {
                    String name = matcher.group(1);
                    if (!name.equals("RGBA32F") && !name.equals("RGB32F") && !name.equals("RGB16")) {
                        LOGGER.warn("[Iris] Ignoring GAUX4FORMAT:{} in '{}' — only RGBA32F, RGB32F and RGB16 are valid; "
                                + "use `const int colortex7Format = {};` instead", name, source.getName(), name);
                        continue;
                    }
                    InternalTextureFormat.fromString(name).ifPresent(format -> {
                        this.renderTargets.setColorFormat(7, format);
                        LOGGER.info("[Iris] Legacy GAUX4FORMAT directive in '{}': colortex7 -> {}",
                                source.getName(), name);
                    });
                }
            }
        }
    }

    /**
     * The stages of {@code source} that a pack directive may be declared in, each with the pack's own preprocessor
     * conditionals resolved against the macro set that stage actually compiles with.
     * <p>
     * <strong>Directives must never be read from raw source.</strong> These scans take the LAST textual match, so a
     * directive the pack declared under a disabled {@code #if} silently wins over the live one. Iris is immune because
     * it scans source JCPP has already preprocessed ({@code ShaderPack.java:317} feeds {@code ProgramSet} ->
     * {@code ConstDirectiveParser}); OptiFine 1.12.2 does not preprocess, but its matchers filter on the VALUE
     * ({@code isConstBoolSuffix("Clear", false)} / {@code ("MipmapEnabled", true)}, {@code Shaders.java:2470/2499}), so
     * it only ever reads the polarity that is not the default and is accidentally immune to the common
     * {@code #if}/{@code #else} pair. Impetus declares {@code IS_IRIS}, so it owes the pack Iris's semantics.
     * <p>
     * Body Camera Shader v1.6.1 is the case that proved it: {@code colortex0Format} (R11F_G11F_B10F vs RGB8),
     * {@code colortex5Clear} (false vs true) and {@code colortex0MipmapEnabled} (true vs false) are each declared once
     * per branch of an {@code #if}, and all three resolved to the dead branch. That killed the pack's auto-exposure
     * (a wiped accumulator makes its `color /= tempExposure + 0.125` a flat 8x) and clipped its HDR buffer, for a
     * uniformly white screen.
     * <p>
     * Option values reach the resolver even though {@code getEnvironmentDefines()} excludes them, because Impetus
     * applies them in place as real {@code #define} lines ahead of the {@code #if} — do NOT add options to the macro
     * map instead, that reintroduces the macro-redefinition failure across every non-Complementary pack.
     * <p>
     * Vertex first, fragment last: the scans are last-match-wins, and Iris only ever trusts the fragment stage
     * ({@code ProgramSet.java:263}), so when the two disagree the fragment value has to be the one that survives.
     * Scanning the vertex stage at all is a deliberate superset of Iris, matching OptiFine, which scans every file in
     * the pack — Sildur declares real formats in a {@code .vsh}.
     */
    private List<String> activeDirectiveStages(ProgramSource source) {
        Map<String, String> macros =
                com.bdmajora.impetus.iris.gl.shader.ShaderMacros.forProgram(this.shaderDefines, source.getName());
        List<String> stages = new ArrayList<>(2);
        for (Optional<String> stage : java.util.Arrays.asList(source.getVertexSource(), source.getFragmentSource())) {
            if (stage.isPresent()) {
                stages.add(GlslPreprocessor.resolveConditionals(stage.get(), macros));
            }
        }
        return stages;
    }

    private void applyPackFormatDirectives(List<ProgramSource> sources) {
        Pattern formatDirective = Pattern.compile("const\\s+int\\s+(\\w+?)Format\\s*=\\s*(\\w+)\\s*;");
        Pattern clearDirective = Pattern.compile("const\\s+bool\\s+(\\w+?)Clear\\s*=\\s*(true|false)\\s*;");
        Pattern clearColorDirective = Pattern.compile("const\\s+vec4\\s+(\\w+?)ClearColor\\s*=\\s*vec4\\s*\\(([^)]*)\\)\\s*;");
        applyLegacyGaux4Format(sources);
        StringBuilder scalarText = new StringBuilder();
        for (ProgramSource source : sources) {
            for (String stage : activeDirectiveStages(source)) {
                // The pack-wide scalar directives are name-keyed rather than target-keyed, so they can be read from
                // one concatenation. Terminate each stage: an unterminated construct must not run into the next.
                scalarText.append(stage).append('\n');
                Matcher matcher = formatDirective.matcher(stage);
                while (matcher.find()) {
                    Integer index = COLOR_TARGETS_BY_NAME.get(matcher.group(1));
                    if (index == null || index >= IrisRenderTargets.MAX_COLOR_BUFFERS) {
                        continue; // not a color-target name (e.g. shadowcolor0Format — shadow pass comes later)
                    }
                    Optional<InternalTextureFormat> format = InternalTextureFormat.fromString(matcher.group(2));
                    if (!format.isPresent()) {
                        LOGGER.warn("[Iris] '{}' requests unknown format {} for colortex{}; keeping RGBA8",
                                source.getName(), matcher.group(2), index);
                        continue;
                    }
                    try {
                        this.renderTargets.setColorFormat(index, format.get());
                        LOGGER.info("[Iris] colortex{} format {}", index, format.get());
                    } catch (IllegalStateException e) {
                        LOGGER.warn("[Iris] Format directive for colortex{} came after the target was created", index);
                    }
                }
                Matcher clearMatcher = clearDirective.matcher(stage);
                while (clearMatcher.find()) {
                    Integer index = COLOR_TARGETS_BY_NAME.get(clearMatcher.group(1));
                    if (index != null && index < IrisRenderTargets.MAX_COLOR_BUFFERS) {
                        this.colorBufferClears[index] = Boolean.parseBoolean(clearMatcher.group(2));
                    }
                }
                Matcher clearColorMatcher = clearColorDirective.matcher(stage);
                while (clearColorMatcher.find()) {
                    Integer index = COLOR_TARGETS_BY_NAME.get(clearColorMatcher.group(1));
                    if (index != null && index < IrisRenderTargets.MAX_COLOR_BUFFERS) {
                        float[] color = parseVec4(clearColorMatcher.group(2));
                        if (color != null) {
                            this.colorBufferClearColors[index] = color;
                        }
                    }
                }
            }
        }
        applyPackScalarDirectives(scalarText.toString());
    }

    /**
     * The pack-wide scalar {@code const} directives Iris collects into {@code PackDirectives}
     * ({@code PackDirectives.acceptDirectivesFrom}). {@code sunPathRotation} and the shadow directives are read on the
     * shadow path instead, which owns their consumers.
     * <p>
     * Defaults and units are Iris's: the half-lives are in <em>deciseconds</em> ({@code SmoothedFloat} scales by
     * {@code 0.1f}), and {@code ambientOcclusionLevel} is clamped to 0..1.
     */
    private void applyPackScalarDirectives(String activeText) {
        this.centerDepthHalfLife = parseConstFloat(activeText, "centerDepthHalflife", DEFAULT_CENTER_DEPTH_HALF_LIFE);

        // noisetex size. A pack that samples `texture2D(noisetex, uv * 32)` against a 256x256 noise texture gets the
        // wrong spatial frequency everywhere it uses noise — Body Camera's water normals are built from it.
        int noiseResolution = parseConstInt(activeText, "noiseTextureResolution", NoiseTexture.DEFAULT_RESOLUTION);
        if (noiseResolution > 0) {
            // NoiseTexture allocates resolution^2 * 4 bytes twice (a byte[] and a direct buffer), so a pack typo like
            // 65536 would OOM the client outright rather than render badly. 4096 is far past anything real.
            if (noiseResolution > 4096) {
                LOGGER.warn("[Iris] Pack requests noiseTextureResolution={}; clamping to 4096", noiseResolution);
                noiseResolution = 4096;
            }
            this.noiseTextureResolution = noiseResolution;
        }

        // Vanilla's baked AO strength. Iris pushes this into WorldRenderingSettings, where the block-model AO
        // computation reads it; 1.0 is vanilla, 0.0 disables vanilla AO so the pack can do its own.
        float aoLevel = parseConstFloat(activeText, "ambientOcclusionLevel", 1.0f);
        this.ambientOcclusionLevel = Math.max(0.0f, Math.min(1.0f, aoLevel));
        com.bdmajora.impetus.iris.material.WorldRenderingSettings
                .setAmbientOcclusionLevel(this.ambientOcclusionLevel);

        // `wetness` and `eyeBrightnessSmooth` smoothing rates.
        this.wetnessHalfLife = parseConstFloat(activeText, "wetnessHalflife", DEFAULT_WETNESS_HALF_LIFE);
        this.drynessHalfLife = parseConstFloat(activeText, "drynessHalflife", DEFAULT_DRYNESS_HALF_LIFE);
        this.eyeBrightnessHalfLife =
                parseConstFloat(activeText, "eyeBrightnessHalflife", DEFAULT_EYE_BRIGHTNESS_HALF_LIFE);
        EyeBrightnessTracker.setHalfLives(this.wetnessHalfLife, this.drynessHalfLife, this.eyeBrightnessHalfLife);

        LOGGER.info("[Iris] Pack directives: noiseTextureResolution={}, ambientOcclusionLevel={}, "
                        + "centerDepthHalflife={}, wetnessHalflife={}, drynessHalflife={}, eyeBrightnessHalflife={}",
                this.noiseTextureResolution, this.ambientOcclusionLevel, this.centerDepthHalfLife,
                this.wetnessHalfLife, this.drynessHalfLife, this.eyeBrightnessHalfLife);
    }

    private static float[] parseVec4(String value) {
        String[] parts = value.split(",");
        try {
            if (parts.length == 1) {
                float scalar = parseFloatLiteral(parts[0]);
                return new float[]{scalar, scalar, scalar, scalar};
            }
            if (parts.length != 4) {
                return null;
            }
            return new float[]{
                    parseFloatLiteral(parts[0]),
                    parseFloatLiteral(parts[1]),
                    parseFloatLiteral(parts[2]),
                    parseFloatLiteral(parts[3])
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static float parseFloatLiteral(String value) {
        String cleaned = value.trim();
        if (cleaned.endsWith("f") || cleaned.endsWith("F")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return Float.parseFloat(cleaned);
    }

    /**
     * Creates every color target the composite/final programs declare a sampler for, so per-pass sampler snapshots can
     * bind them even when the writing pass comes later in the chain.
     */
    private void materializeSampledTargets(List<ProgramSource> sources) {
        StringBuilder allSource = new StringBuilder();
        for (ProgramSource source : sources) {
            source.getVertexSource().ifPresent(allSource::append);
            source.getFragmentSource().ifPresent(allSource::append);
        }
        String text = allSource.toString();
        for (Map.Entry<String, Integer> entry : COLOR_TARGETS_BY_NAME.entrySet()) {
            int target = entry.getValue();
            if (Pattern.compile("\\bsampler2D\\s+" + entry.getKey() + "\\b").matcher(text).find()) {
                this.renderTargets.getOrCreate(target);
            }
        }
    }

    /**
     * Builds the shadow renderer when the pack declares a {@code shadow} program, with the OptiFine shadow projection
     * directives parsed anywhere in the pack sources.
     */
    private IrisShadowRenderer createShadowRenderer(ShaderPack pack) {
        // Const directives may live in ANY source (packs put them in shared includes flattened into every program),
        // so scan the gbuffer programs and the whole fullscreen chain.
        StringBuilder allSources = new StringBuilder();
        StringBuilder activeSources = new StringBuilder();
        for (ProgramId id : new ProgramId[]{ProgramId.Shadow, ProgramId.Terrain, ProgramId.Water, ProgramId.Final}) {
            pack.getProgramSet().get(id).ifPresent(source -> {
                appendDirectiveSource(allSources, activeSources, source, source.getVertexSource());
                appendDirectiveSource(allSources, activeSources, source, source.getFragmentSource());
            });
        }
        for (ProgramSource source : collectFullscreenSources(pack)) {
            appendDirectiveSource(allSources, activeSources, source, source.getFragmentSource());
        }
        // `text` keeps the raw sources for the `#define`-form directives, which conditional resolution consumes.
        String text = allSources.toString();
        String activeText = activeSources.toString();

        // Tilt of the sun/moon's daily arc. Needed by the celestial-position uniforms whether or not the pack draws
        // shadows, so set it before the no-shadow early-out.
        float sunPathRotation = parseConstFloat(activeText, "sunPathRotation", 0.0f);
        CelestialUniforms.setSunPathRotation(sunPathRotation);

        Optional<ProgramSource> shadowSource = pack.getProgramSet().get(ProgramId.Shadow);
        if (!shadowSource.isPresent()) {
            LOGGER.info("[Iris] Pack declares no shadow program; shadow mapping disabled (always-lit stub in use)");
            return null;
        }
        // OptiFine's pre-const spelling of the same three settings: `#define SHADOWRES 2048` etc. Iris accepts both
        // (PackShadowDirectives), and the shaders.properties keys override either.
        int resolution = parseConstInt(activeText, "shadowMapResolution", parseDefineInt(text, "SHADOWRES", 1024));
        if (resolution <= 0) {
            // A pack error, but the literal grammar now admits a sign, and a non-positive texture size would fail
            // allocation rather than degrade. E-LITE declares `shadowMapResolution = 10` in its shadows-off branch,
            // which conditional resolution already hides; this only backstops the value actually reaching GL.
            LOGGER.warn("[Iris] Pack declares shadowMapResolution={}; falling back to 1024", resolution);
            resolution = 1024;
        }
        float distance = parseConstFloat(activeText, "shadowDistance", parseDefineFloat(text, "SHADOWHPL", 160.0f));
        float nearPlane = parseConstFloat(activeText, "shadowNearPlane", IrisShadowRenderer.DEFAULT_NEAR_PLANE);
        float farPlane = parseConstFloat(activeText, "shadowFarPlane", IrisShadowRenderer.DEFAULT_FAR_PLANE);
        float intervalSize = parseConstFloat(activeText, "shadowIntervalSize", IrisShadowRenderer.DEFAULT_INTERVAL_SIZE);
        Float shadowMapFov = parseConstFloat(activeText, "shadowMapFov");
        if (shadowMapFov == null) {
            Matcher legacyFov = Pattern.compile("(?m)^\\s*#define\\s+SHADOWFOV\\s+([0-9.]+)").matcher(text);
            if (legacyFov.find()) {
                try {
                    shadowMapFov = Float.parseFloat(legacyFov.group(1));
                } catch (NumberFormatException ignored) {
                    // keep the const-derived value (null = no FOV override)
                }
            }
        }
        // shaders.properties wins over anything declared in GLSL, matching Iris's directive precedence.
        resolution = pack.getProperties().getShadowMapResolution().orElse(resolution);
        distance = pack.getProperties().getShadowDistance().isPresent()
                ? pack.getProperties().getShadowDistance().getAsInt() : distance;
        // `const float voxelDistance` overrides the shadow distance for voxelization only: packs that voxelize for
        // colored lighting want a tighter radius than their shadow map covers (Iris PackShadowDirectives).
        float voxelDistance = parseConstFloat(activeText, "voxelDistance", 0.0f);
        // `shadowDistanceRenderMul` scales the shadow pass's CULLING distance (not the projection). Iris's unset
        // sentinel is -1, where it falls back to the user's shadow-distance setting; there is no such setting here,
        // so an unset or negative value simply means "no scaling".
        float shadowDistanceRenderMul = parseConstFloat(activeText, "shadowDistanceRenderMul", -1.0f);
        float cullDistance = shadowDistanceRenderMul >= 0.0f ? distance * shadowDistanceRenderMul : distance;
        if (shadowDistanceRenderMul >= 0.0f && shadowDistanceRenderMul != 1.0f) {
            LOGGER.info("[Iris] shadowDistanceRenderMul={} scales the shadow culling distance to {} blocks",
                    shadowDistanceRenderMul, cullDistance);
        }
        float voxelRadius = voxelDistance > 0.0f ? voxelDistance : distance;
        com.bdmajora.impetus.iris.material.WorldRenderingSettings.setVoxelRenderDistanceChunks(
                Math.max(1, Math.round(voxelRadius / 16.0f)));
        if (voxelDistance > 0.0f) {
            LOGGER.info("[Iris] voxelDistance={} overrides shadowDistance={} for voxelization", voxelDistance, distance);
        }
        parseShadowDepthSamplingSettings(activeText);
        // The FF shadow program (entities/block entities) belongs to the gbuffers custom-texture stage.
        Map<String, Integer> shadowSamplerUnits = new LinkedHashMap<>(GBUFFER_SAMPLER_UNITS);
        shadowSamplerUnits.putAll(gbufferSamplerOverrideUnits());
        try {
            ShadowContentSettings content = ShadowContentSettings.from(pack.getProperties());
            LOGGER.info("[Iris] Shadow pass content: {}", content);
            // Iris's voxelization detection: a shadow geometry stage, or the pack declaring custom images.
            boolean packVoxelizes = shadowSource.get().getGeometrySource().isPresent()
                    || !pack.getProperties().getIrisCustomImages().isEmpty();
            return new IrisShadowRenderer(resolution, distance, nearPlane, farPlane, intervalSize, shadowMapFov,
                    sunPathRotation,
                    shadowSource.get(), shadowSamplerUnits, this.shaderDefines,
                    this.shadowHardwareFiltering, this.shadowMipmap, this.shadowNearest,
                    this.separateHardwareSamplers, this::bindShaderPackResources, content,
                    voxelDistance, cullDistance, packVoxelizes);
        } catch (Exception e) {
            LOGGER.error("[Iris] Failed to create the shadow renderer; shadows disabled", e);
            return null;
        }
    }

    /**
     * Appends one shader stage to both directive scans: {@code raw} as authored, {@code active} with the pack's own
     * preprocessor conditionals resolved against the macro set that stage compiles with.
     * <p>
     * The {@code const} directives must be read from {@code active}, because a raw first-textual-match happily reads a
     * value the pack disabled. Complementary Reimagined declares {@code const int shadowMapResolution = 4096;} under
     * {@code #if SHADOW_QUALITY >= 5 || SHADOW_SMOOTHING < 3} and {@code 2048} under its {@code #else}; at its default
     * 3/4 the 4096 map we allocated left every {@code texelFetch(shadowtex0, ivec2(pos * shadowMapResolution))} in the
     * pack — its volumetric light shafts, and the scene-aware light-shaft probe — addressing one quadrant of the map.
     * That quadrant is mostly cleared depth, which reads as "lit", so light shafts shone straight through terrain.
     * Normalized {@code shadow2D} lookups are resolution-independent, which is why surface shadows looked correct.
     */
    private void appendDirectiveSource(StringBuilder raw, StringBuilder active, ProgramSource source,
                                       Optional<String> stage) {
        if (!stage.isPresent()) {
            return;
        }
        raw.append(stage.get());
        active.append(GlslPreprocessor.resolveConditionals(stage.get(),
                com.bdmajora.impetus.iris.gl.shader.ShaderMacros.forProgram(this.shaderDefines, source.getName())));
        // Stages are resolved individually, so terminate the appended text: an unterminated construct in one file
        // must not run into the next.
        active.append('\n');
    }

    /**
     * The GLSL float literal grammar, as permissive as {@link Float#parseFloat}: optional sign, {@code .5} and
     * {@code 1.} forms, and an exponent. The narrower {@code -?[0-9]+(\.[0-9]+)?} this replaces silently fell back to
     * the default for a pack writing {@code const float x = .5;} or {@code 1e-3}.
     */
    private static final String FLOAT_LITERAL = "([-+]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?)[fF]?";

    /**
     * {@return the LAST match of {@code pattern} in {@code text}, or {@code null}}
     * <p>
     * Last-wins is Iris's and OptiFine's rule: Iris dispatches every directive it finds in file order and each
     * overwrites the previous ({@code DispatchingDirectiveHolder}), OptiFine likewise assigns per line
     * ({@code Shaders.java:2555}). These scans read text concatenated from several stages/programs, so a pack that
     * declares a directive more than once must resolve the same way it does under Iris. First-match-wins was the old
     * behaviour and is a silent divergence whenever the values differ.
     */
    private static String lastMatch(Pattern pattern, String text, int group) {
        Matcher matcher = pattern.matcher(text);
        String value = null;
        while (matcher.find()) {
            value = matcher.group(group);
        }
        return value;
    }

    private static int parseConstInt(String text, String name, int fallback) {
        String value = lastMatch(Pattern.compile("const\\s+int\\s+" + name + "\\s*=\\s*([-+]?\\d+)"), text, 1);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** OptiFine's legacy {@code #define <NAME> <value>} spelling of a shadow directive. */
    private static int parseDefineInt(String text, String name, int fallback) {
        Matcher matcher = Pattern.compile("(?m)^\\s*#define\\s+" + name + "\\s+(\\d+)").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private static float parseDefineFloat(String text, String name, float fallback) {
        Matcher matcher = Pattern.compile("(?m)^\\s*#define\\s+" + name + "\\s+([0-9.]+)").matcher(text);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Float.parseFloat(matcher.group(1));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseConstBool(String text, String name) {
        return parseOptionalConstBool(text, name).orElse(false);
    }

    private static Optional<Boolean> parseOptionalConstBool(String text, String name) {
        String value = lastMatch(Pattern.compile("const\\s+bool\\s+" + name + "\\s*=\\s*(true|false)"), text, 1);
        return value != null ? Optional.of(Boolean.parseBoolean(value)) : Optional.empty();
    }

    private void parseShadowDepthSamplingSettings(String text) {
        Arrays.fill(this.shadowHardwareFiltering, false);
        Arrays.fill(this.shadowMipmap, false);
        Arrays.fill(this.shadowNearest, false);

        applyBothThenIndexed(text, "shadowHardwareFiltering", "shadowHardwareFiltering", this.shadowHardwareFiltering);
        applyBothThenIndexed(text, "generateShadowMipmap", "shadowtex", "Mipmap", this.shadowMipmap);
        parseOptionalConstBool(text, "shadowtexMipmap").ifPresent(value -> this.shadowMipmap[0] = value);
        applyBothThenIndexed(text, null, "shadowtex", "Nearest", this.shadowNearest);
        parseOptionalConstBool(text, "shadowtexNearest").ifPresent(value -> this.shadowNearest[0] = value);
        for (int i = 0; i < this.shadowNearest.length; i++) {
            final int index = i;
            parseOptionalConstBool(text, "shadow" + i + "MinMagNearest")
                    .ifPresent(value -> this.shadowNearest[index] = value);
        }
    }

    private static void applyBothThenIndexed(String text, String bothName, String indexedPrefix, boolean[] values) {
        if (bothName != null) {
            parseOptionalConstBool(text, bothName).ifPresent(value -> Arrays.fill(values, value));
        }
        for (int i = 0; i < values.length; i++) {
            final int index = i;
            parseOptionalConstBool(text, indexedPrefix + i).ifPresent(value -> values[index] = value);
        }
    }

    private static void applyBothThenIndexed(String text, String bothName, String indexedPrefix,
                                             String indexedSuffix, boolean[] values) {
        if (bothName != null) {
            parseOptionalConstBool(text, bothName).ifPresent(value -> Arrays.fill(values, value));
        }
        for (int i = 0; i < values.length; i++) {
            final int index = i;
            parseOptionalConstBool(text, indexedPrefix + i + indexedSuffix)
                    .ifPresent(value -> values[index] = value);
        }
    }

    private static float parseConstFloat(String text, String name, float fallback) {
        Float value = parseConstFloat(text, name);
        return value != null ? value : fallback;
    }

    /**
     * {@return the pack's {@code const float <name>}, or {@code null} when it declares none}
     * <p>
     * Also accepts {@code const int <name>} for the float-valued directives: GLSL would reject the implicit narrowing,
     * but packs write {@code const float shadowDistance = 120;} anyway and both Iris (Float.parseFloat over the token)
     * and OptiFine ({@code isConstFloat} on a value it later parses loosely) tolerate it.
     */
    private static Float parseConstFloat(String text, String name) {
        String value = lastMatch(
                Pattern.compile("const\\s+(?:float|int)\\s+" + name + "\\s*=\\s*" + FLOAT_LITERAL), text, 1);
        if (value == null) {
            return null;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The color buffers the terrain program writes, per its {@code DRAWBUFFERS} directive. */
    private int[] terrainDrawBuffers(ShaderPack pack) {
        return programDrawBuffers(pack, ProgramId.Terrain, "gbuffers_terrain");
    }

    /**
     * One gbuffer program's {@code DRAWBUFFERS} mask, resolved against the macros that program actually compiles
     * with. The scoping matters: a program whose pre-Iris branch is active (see
     * {@code ShaderMacros.setPackLegacyPrograms}) declares a different mask than its Iris branch — Sildur's
     * {@code gbuffers_water} is {@code 41} with {@code IS_IRIS} and {@code 412} without — and a buffer missing from
     * the attachment set gets rerouted to colortex0, corrupting it.
     */
    private int[] programDrawBuffers(ShaderPack pack, ProgramId id, String fallbackName) {
        ProgramSource source = pack.getProgramSet().get(id).orElse(null);
        String fragment = source == null ? null : source.getFragmentSource().orElse(null);
        String name = source == null ? fallbackName : source.getName();
        if (fragment == null) {
            return DrawBuffers.DEFAULT.clone();
        }
        return sanitizeDrawBuffers(name, DrawBuffers.parseActive(fragment,
                com.bdmajora.impetus.iris.gl.shader.ShaderMacros.forProgram(this.shaderDefines, name)));
    }

    /**
     * The union of every gbuffer-stage program's {@code DRAWBUFFERS} mask (terrain, water, and the fixed-function
     * programs), plus {@code colortex0}. All of these must be attached to the gbuffer FBOs up front, because a
     * draw-buffer mask naming an attachment without an image makes the FBO incomplete.
     */
    private int[] computeGbufferAttachments(ShaderPack pack, int[] terrainDrawBuffers) {
        TreeSet<Integer> attachments = new TreeSet<>();
        attachments.add(0); // colortex0 must exist — it is what final/blit shows
        for (int buffer : terrainDrawBuffers) {
            attachments.add(buffer);
        }
        for (int buffer : programDrawBuffers(pack, ProgramId.Water, "gbuffers_water")) {
            attachments.add(buffer);
        }
        for (GbufferPrograms.Entry entry : this.gbufferPrograms.entries()) {
            for (int buffer : entry.getDrawBuffers()) {
                attachments.add(buffer);
            }
        }
        int[] result = new int[attachments.size()];
        int i = 0;
        for (int buffer : attachments) {
            result[i++] = buffer;
        }
        LOGGER.info("[Iris] Gbuffer attachments {}", java.util.Arrays.toString(result));
        return result;
    }

    /**
     * A gbuffer FBO world rendering is redirected into: every gbuffer-stage color target attached (writing the current
     * "front" side of each under the given flip state) plus the shared depth texture. The draw-buffer mask starts as
     * plain-color-only; {@link #setPhase} and the terrain override switch it per program, OptiFine-style.
     */
    private IrisFramebuffer createGbufferFramebuffer(BufferFlipper flipper) {
        IrisFramebuffer framebuffer = new IrisFramebuffer();
        for (Map.Entry<Integer, Integer> entry : this.gbufferAttachmentPoints.entrySet()) {
            int logicalIndex = entry.getKey();
            int attachmentPoint = entry.getValue();
            framebuffer.addColorAttachment(logicalIndex, attachmentPoint, frontTexture(flipper, logicalIndex));
        }
        framebuffer.addDepthAttachment(this.renderTargets.getDepthTexture().getTextureId());
        drawGbufferBuffers(framebuffer, FIXED_FUNCTION_MASK);
        checkFramebufferComplete(framebuffer, "gbuffer", this.gbufferAttachments);
        return framebuffer;
    }

    private static void checkFramebufferComplete(IrisFramebuffer framebuffer, String purpose, int[] buffers) {
        int status = framebuffer.getStatus();
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Incomplete Iris " + purpose + " framebuffer for buffers "
                    + Arrays.toString(buffers) + ": status=" + status);
        }
    }

    private void drawGbufferBuffers(IrisFramebuffer framebuffer, int[] logicalDrawBuffers) {
        int[] physicalDrawBuffers = new int[logicalDrawBuffers.length];
        java.util.Set<Integer> written = new java.util.HashSet<>();
        for (int i = 0; i < logicalDrawBuffers.length; i++) {
            Integer attachmentPoint = this.gbufferAttachmentPoints.get(logicalDrawBuffers[i]);
            if (attachmentPoint == null) {
                LOGGER.warn("[Iris] Gbuffer draw buffer colortex{} is not attached; routing output slot {} to colortex0",
                        logicalDrawBuffers[i], i);
                attachmentPoint = this.gbufferAttachmentPoints.get(0);
            } else {
                written.add(logicalDrawBuffers[i]);
            }
            physicalDrawBuffers[i] = attachmentPoint == null ? 0 : attachmentPoint;
        }
        // Iris parity: a gbuffer FBO must hold ONLY the buffers the current program writes, so a program that samples a
        // colortex it doesn't write (gbuffers_terrain reading gaux4=colortex7 for fog) reads a detached — thus valid —
        // texture instead of triggering a feedback loop that returns garbage (the ~50 that blew the horizon white).
        framebuffer.retainColorAttachments(written);
        framebuffer.drawBuffers(physicalDrawBuffers);
    }

    /**
     * Bakes one frame's ping-pong schedule from the flipper's current state, advancing the flipper as it goes.
     * Family order matches Iris: {@code begin}, {@code prepare}, (gbuffers), {@code deferred}, (translucents),
     * {@code composite}, {@code final}.
     */
    private void buildSchedule(ShaderPack pack, BufferFlipper flipper) {
        buildFamily(pack, ProgramArrayId.Setup, TextureStage.SETUP, flipper, this.setupPasses);
        buildFamily(pack, ProgramArrayId.Begin, TextureStage.BEGIN, flipper, this.beginPasses);
        buildComputePasses(pack, flipper);
        buildFamily(pack, ProgramArrayId.Prepare, TextureStage.PREPARE, flipper, this.preparePasses);

        this.gbufferFramebuffer = createGbufferFramebuffer(flipper);
        this.preTranslucentGbufferSamplerFlips = flipper.snapshot();

        applyExplicitPreFlips(pack.getProperties().getExplicitFlips("deferred_pre"), flipper, "deferred_pre");
        buildFamily(pack, ProgramArrayId.Deferred, TextureStage.DEFERRED, flipper, this.deferredPasses);

        this.translucentGbufferSamplerFlips = flipper.snapshot();
        this.translucentGbufferFramebuffer =
                this.deferredPasses.isEmpty() ? this.gbufferFramebuffer : createGbufferFramebuffer(flipper);

        applyExplicitPreFlips(pack.getProperties().getExplicitFlips("composite_pre"), flipper, "composite_pre");
        buildFamily(pack, ProgramArrayId.Composite, TextureStage.COMPOSITE_AND_FINAL, flipper, this.passes);

        FullscreenPass finalPass = buildFinalPass(pack, flipper);
        if (finalPass != null) {
            this.passes.add(finalPass);
        } else {
            // No (working) final program: show the post-composite colortex0 by blitting it to the screen.
            this.blitSourceFramebuffer = new IrisFramebuffer();
            this.blitSourceFramebuffer.addColorAttachment(0, frontTexture(flipper, 0));
            this.blitSourceFramebuffer.readBuffer(0);
            checkFramebufferComplete(this.blitSourceFramebuffer, "fallback blit", new int[]{0});
        }
    }

    /**
     * Schedules one numbered family in index order. An entry with a vertex+fragment pair becomes a drawing pass; an
     * entry the pack only supplies {@code .csh} files for becomes a compute-only pass (Iris's {@code ComputeOnlyPass}),
     * which is how Photon's {@code deferred4_a.csh} gets to run at all — there is no {@code deferred4_a.fsh}.
     * Either way the entry's computes are dispatched before the entry's own draw, under the same flip state.
     */
    private void buildFamily(ShaderPack pack, ProgramArrayId id, TextureStage stage, BufferFlipper flipper,
                             List<FullscreenPass> target) {
        for (int i = 0; i < id.getNumPrograms(); i++) {
            Optional<ProgramSource> source = pack.getProgramSet().get(id, i);
            if (!source.isPresent()) {
                continue;
            }
            String name = source.get().getName();
            if (!isProgramEnabled(pack, name)) {
                LOGGER.info("[Iris] Skipping disabled pass '{}'", name);
                continue;
            }
            List<ComputePass> computes = buildFamilyComputePasses(pack, source.get(), stage);
            if (!source.get().hasRasterStages()) {
                if (!computes.isEmpty()) {
                    target.add(FullscreenPass.computeOnly(name, snapshotFrontTextures(flipper), flipper.snapshot(),
                            computes));
                }
                continue;
            }
            FullscreenPass pass = buildCompositePass(pack, source.get(), flipper, computes);
            if (pass != null) {
                target.add(pass);
            } else if (!computes.isEmpty()) {
                // The draw failed to compile but the computes linked: still run them, as Iris would.
                target.add(FullscreenPass.computeOnly(name, snapshotFrontTextures(flipper), flipper.snapshot(),
                        computes));
            }
        }
    }

    /**
     * Gives a pass the viewport of the buffers it writes. Iris throws when a pass mixes differently-sized draw
     * buffers; here the mismatch is logged and the first size wins, because refusing to build the pass would take a
     * whole stage of the chain out rather than render it at a slightly wrong scale.
     */
    private void applyPassViewport(FullscreenPass pass, int[] drawBuffers) {
        for (int buffer : drawBuffers) {
            if (buffer < 0 || buffer >= IrisRenderTargets.MAX_COLOR_BUFFERS
                    || !this.renderTargets.hasCustomSize(buffer)) {
                continue;
            }
            int width = this.renderTargets.getWidth(buffer);
            int height = this.renderTargets.getHeight(buffer);
            if (pass.viewportWidth == 0) {
                pass.viewportWidth = width;
                pass.viewportHeight = height;
                LOGGER.info("[Iris] Pass '{}' renders at {}x{} (colortex{} declares its own size)",
                        pass.name, width, height, buffer);
            } else if (pass.viewportWidth != width || pass.viewportHeight != height) {
                LOGGER.warn("[Iris] Pass '{}' writes buffers of different sizes ({}x{} vs colortex{} at {}x{}); "
                                + "using the first", pass.name, pass.viewportWidth, pass.viewportHeight,
                        buffer, width, height);
            }
        }
    }

    private void applyExplicitPreFlips(Map<Integer, Boolean> explicitFlips, BufferFlipper flipper, String name) {
        for (Map.Entry<Integer, Boolean> entry : explicitFlips.entrySet()) {
            if (entry.getValue()) {
                flipper.flip(entry.getKey());
                LOGGER.info("[Iris] Explicit pre-flip '{}': colortex{}", name, entry.getKey());
            }
        }
    }

    /**
     * Iris {@code FinalPassRenderer.SwapPass}: every buffer the chain leaves odd-flipped ends the frame with its
     * latest content on the ALT side, so copy alt->main after the final pass. Buffers cleared at frame start are
     * skipped, matching Iris. Do not special-case gbuffer attachments here: packs such as Complementary deliberately
     * mark some gbuffer-written targets (for example colortex4) as clear=false so their temporal contents survive.
     */
    private void buildSwapPasses(BufferFlipper flipper) {
        for (int i = 0; i < IrisRenderTargets.MAX_COLOR_BUFFERS; i++) {
            if (!flipper.isFlipped(i) || this.renderTargets.get(i) == null || this.colorBufferClears[i]) {
                if (flipper.isFlipped(i) && this.renderTargets.get(i) != null) {
                    LOGGER.info("[Iris] colortex{} ends odd-flipped but is clear=true; no swap copy is needed", i);
                }
                continue;
            }
            IrisRenderTarget target = this.renderTargets.get(i);
            IrisFramebuffer from = new IrisFramebuffer();
            from.addColorAttachment(0, target.getAltTexture());
            from.readBuffer(0);
            checkFramebufferComplete(from, "swap colortex" + i, new int[]{i});
            this.swapPasses.add(new SwapPass(i, from, target.getMainTexture(),
                    this.renderTargets.getWidth(i), this.renderTargets.getHeight(i)));
            LOGGER.info("[Iris] colortex{} ends the frame odd-flipped and clear=false; swap pass (alt->main copy) added",
                    i);
        }
    }

    private void buildClearPasses() {
        for (int i = 0; i < IrisRenderTargets.MAX_COLOR_BUFFERS; i++) {
            if (this.renderTargets.get(i) == null) {
                continue;
            }
            float[] color = defaultClearColor(i);
            int clearWidth = this.renderTargets.hasCustomSize(i) ? this.renderTargets.getWidth(i) : 0;
            int clearHeight = this.renderTargets.hasCustomSize(i) ? this.renderTargets.getHeight(i) : 0;
            this.fullClearPasses.add(new ClearPass(
                    this.renderTargets.createClearFramebuffer(false, new int[]{i}), color, clearWidth, clearHeight));
            this.fullClearPasses.add(new ClearPass(
                    this.renderTargets.createClearFramebuffer(true, new int[]{i}), color, clearWidth, clearHeight));
            if (this.colorBufferClears[i]) {
                this.clearPasses.add(new ClearPass(
                        this.renderTargets.createClearFramebuffer(false, new int[]{i}), color, clearWidth, clearHeight));
                this.clearPasses.add(new ClearPass(
                        this.renderTargets.createClearFramebuffer(true, new int[]{i}), color, clearWidth, clearHeight));
            }
        }
    }

    private boolean isGbufferAttachment(int index) {
        return this.gbufferAttachmentPoints.containsKey(index);
    }

    private float[] defaultClearColor(int index) {
        if (this.colorBufferClearColors[index] != null) {
            return this.colorBufferClearColors[index];
        }
        if (index == 0) {
            return null; // Iris clears colortex0 to the current fog color by default.
        }
        if (index == 1) {
            return new float[]{1.0f, 1.0f, 1.0f, 1.0f};
        }
        return new float[]{0.0f, 0.0f, 0.0f, 0.0f};
    }

    private void logRenderTargetSchedule(BufferFlipper flipper) {
        LOGGER.info("[Iris] Gbuffer sampler flips: preTranslucent={}, translucent={}",
                formatBitSet(this.preTranslucentGbufferSamplerFlips),
                formatBitSet(this.translucentGbufferSamplerFlips));
        LOGGER.info("[Iris] End-of-schedule flipped buffers: {}", formatBitSet(flipper.snapshot()));
        for (int i = 0; i < IrisRenderTargets.MAX_COLOR_BUFFERS; i++) {
            IrisRenderTarget target = this.renderTargets.get(i);
            if (target == null) {
                continue;
            }
            LOGGER.info("[Iris] Target colortex{}: format={}, clearAtFrameStart={}, gbufferAttachment={}, flippedAtEnd={}, mainTex={}, altTex={}, defaultClear={}",
                    i, target.getInternalFormat(), this.colorBufferClears[i], isGbufferAttachment(i),
                    flipper.isFlipped(i), target.getMainTexture(), target.getAltTexture(),
                    formatClearColor(defaultClearColor(i)));
        }
        for (SwapPass swap : this.swapPasses) {
            LOGGER.info("[Iris] Swap copy scheduled: colortex{} alt/readFbo -> mainTex{}", swap.index, swap.targetTexture);
        }
    }

    /**
     * The custom-texture stage a full-screen program belongs to, from its source name: {@code beginN} → begin,
     * {@code prepareN} → prepare, {@code deferredN} → deferred, {@code compositeN}/{@code final} → composite.
     */
    private static TextureStage fullscreenTextureStage(String name) {
        if (name.startsWith(ProgramArrayId.Begin.getBaseName())) {
            return TextureStage.BEGIN;
        }
        if (name.startsWith(ProgramArrayId.Prepare.getBaseName())) {
            return TextureStage.PREPARE;
        }
        if (name.startsWith(ProgramArrayId.Deferred.getBaseName())) {
            return TextureStage.DEFERRED;
        }
        if (name.startsWith(ProgramArrayId.ShadowComposite.getBaseName())) {
            return TextureStage.SHADOWCOMP;
        }
        return TextureStage.COMPOSITE_AND_FINAL;
    }

    private static String formatBitSet(BitSet bitSet) {
        List<Integer> values = new ArrayList<>();
        for (int bit = bitSet.nextSetBit(0); bit >= 0; bit = bitSet.nextSetBit(bit + 1)) {
            values.add(bit);
        }
        return values.toString();
    }

    private static String formatClearColor(float[] color) {
        return color == null ? "fog" : Arrays.toString(color);
    }

    private static String summarizeSamplers(int[] samplers) {
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (int i = 0; i < samplers.length; i++) {
            if (samplers[i] == 0) {
                continue;
            }
            if (!first) {
                builder.append(", ");
            }
            builder.append("colortex").append(i).append("->tex").append(samplers[i]);
            first = false;
        }
        return builder.append(']').toString();
    }

    /**
     * Compiles a fullscreen program (and its uniforms) once and caches it by source name. Returns {@code null} if
     * the program failed to compile (cached as absent so we don't retry).
     */
    private IrisProgram cachedProgram(ProgramSource source) {
        String name = source.getName();
        if (this.compiledPrograms.containsKey(name)) {
            return this.compiledPrograms.get(name);
        }
        IrisProgram program = compileFullscreenProgram(source, fullscreenTextureStage(name));
        this.compiledPrograms.put(name, program);
        if (program != null) {
            this.compiledUniforms.put(name, buildUniforms(name, program));
        }
        return program;
    }

    private FullscreenPass buildCompositePass(ShaderPack pack, ProgramSource source, BufferFlipper flipper,
                                              List<ComputePass> computes) {
        String name = source.getName();
        try {
            IrisProgram program = cachedProgram(source);
            if (program == null) {
                return null;
            }
            int[] drawBuffers = sanitizeCompositeDrawBuffers(name, program.getDrawBuffers());
            Map<Integer, Boolean> explicitFlips = pack.getProperties().getExplicitFlips(name);
            BitSet flipsBefore = flipper.snapshot();
            BitSet mipmappedBuffers = parseMipmappedBuffers(source);

            // Reads see the current "front" side; the FBO writes the back side; then the written buffers flip.
            int[] colorSamplers = snapshotFrontTextures(flipper);
            IrisFramebuffer framebuffer = this.renderTargets.createColorFramebuffer(drawBuffers);
            for (int buffer : drawBuffers) {
                if (explicitFlips.get(buffer) == Boolean.FALSE) {
                    continue;
                }
                flipper.flip(buffer);
                // Later passes' colortex custom-texture overrides deactivate for buffers a pass has written.
                this.flippedAtLeastOnce.add(buffer);
            }
            for (Map.Entry<Integer, Boolean> entry : explicitFlips.entrySet()) {
                if (entry.getValue()) {
                    flipper.flip(entry.getKey());
                    this.flippedAtLeastOnce.add(entry.getKey());
                }
            }
            BitSet flipsAfter = flipper.snapshot();
            LOGGER.info("[Iris] Scheduled pass '{}': drawBuffers={}, flipsBefore={}, flipsAfter={}, mipmaps={}, samplerSummary={}",
                    name, Arrays.toString(drawBuffers), formatBitSet(flipsBefore), formatBitSet(flipsAfter),
                    formatBitSet(mipmappedBuffers), summarizeSamplers(colorSamplers));

            FullscreenPass pass = new FullscreenPass(name, program, this.compiledUniforms.get(name), framebuffer,
                    colorSamplers, drawBuffers, ProgramBlendState.from(pack.getProperties(), name),
                    flipsBefore, flipsAfter, mipmappedBuffers, computes);
            applyPassViewport(pass, drawBuffers);
            return pass;
        } catch (Exception e) {
            LOGGER.error("[Iris] Failed to build pass '{}'; it will be skipped: {}", name, e.getMessage());
            return null;
        }
    }

    private FullscreenPass buildFinalPass(ShaderPack pack, BufferFlipper flipper) {
        Optional<ProgramSource> source = pack.getProgramSet().get(ProgramId.Final);
        if (!source.isPresent()) {
            return null;
        }
        String name = source.get().getName();
        if (!isProgramEnabled(pack, name)) {
            LOGGER.info("[Iris] Skipping disabled final pass '{}'", name);
            return null;
        }
        try {
            IrisProgram program = cachedProgram(source.get());
            if (program == null) {
                return null;
            }
            BitSet flips = flipper.snapshot();
            BitSet mipmappedBuffers = parseMipmappedBuffers(source.get());
            int[] colorSamplers = snapshotFrontTextures(flipper);
            LOGGER.info("[Iris] Scheduled final pass '{}': flips={}, mipmaps={}, samplerSummary={}",
                    name, formatBitSet(flips), formatBitSet(mipmappedBuffers), summarizeSamplers(colorSamplers));
            return new FullscreenPass(name, program, this.compiledUniforms.get(name), null,
                    colorSamplers, DrawBuffers.DEFAULT.clone(),
                    ProgramBlendState.from(pack.getProperties(), name), flips, (BitSet) flips.clone(),
                    mipmappedBuffers, java.util.Collections.<ComputePass>emptyList());
        } catch (Exception e) {
            LOGGER.error("[Iris] Failed to build final pass; falling back to colortex0 blit: {}", e.getMessage());
            return null;
        }
    }

    /**
     * The {@code colortexNMipmapEnabled} set for one pass. Per program, like Iris's {@code ProgramDirectives}, and read
     * from the fragment stage with the pack's conditionals resolved — see {@link #activeDirectiveStages} for why raw
     * source is not safe here (Body Camera declares {@code colortex0MipmapEnabled} true and false in the two branches
     * of one {@code #if}, and the dead branch was winning, which broke its auto-exposure metering).
     */
    private BitSet parseMipmappedBuffers(ProgramSource source) {
        BitSet mipmappedBuffers = new BitSet(IrisRenderTargets.MAX_COLOR_BUFFERS);
        Optional<String> fragmentSource = source.getFragmentSource();
        if (!fragmentSource.isPresent()) {
            return mipmappedBuffers;
        }

        Matcher matcher = MIPMAP_DIRECTIVE.matcher(GlslPreprocessor.resolveConditionals(fragmentSource.get(),
                com.bdmajora.impetus.iris.gl.shader.ShaderMacros.forProgram(this.shaderDefines, source.getName())));
        while (matcher.find()) {
            Integer index = colorTargetIndex(matcher.group(1));
            if (index == null || index >= IrisRenderTargets.MAX_COLOR_BUFFERS) {
                continue;
            }
            if (Boolean.parseBoolean(matcher.group(2))) {
                mipmappedBuffers.set(index);
            } else {
                mipmappedBuffers.clear(index);
            }
        }
        return mipmappedBuffers;
    }

    private static Integer colorTargetIndex(String name) {
        if (name.startsWith("colortex")) {
            try {
                return Integer.parseInt(name.substring("colortex".length()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        for (int i = 0; i < LEGACY_COLOR_TARGETS.length; i++) {
            if (LEGACY_COLOR_TARGETS[i].equals(name)) {
                return i;
            }
        }
        return null;
    }

    private IrisProgram compileFullscreenProgram(ProgramSource source, TextureStage stage) {
        String vshRaw = source.getVertexSource().orElse(null);
        String fshRaw = source.getFragmentSource().orElse(null);
        if (vshRaw == null || fshRaw == null) {
            LOGGER.warn("[Iris] Program '{}' is missing a vertex or fragment stage; skipping", source.getName());
            return null;
        }
        // Raw texture.<stage>.<sampler> directives redirect the identifier to its minted customtexN name, but only
        // where the declared sampler type matches the directive's target. Must precede everything else so the later
        // transforms and the DRAWBUFFERS parse all see the final identifier set.
        vshRaw = CustomTextureTransformer.transform(source.getName(), vshRaw, stage);
        fshRaw = CustomTextureTransformer.transform(source.getName(), fshRaw, stage);
        // Modern (1.17+) attribute/matrix names -> fixed-function built-ins. The full-screen quad is drawn with the
        // ortho projection pushed in runPass, so gl_ProjectionMatrix is exactly the (0,1)->(-1,1) matrix Iris
        // substitutes for `projectionMatrix` in its own composite core transformer.
        vshRaw = VanillaNameTransformer.transform(vshRaw);
        fshRaw = VanillaNameTransformer.transform(fshRaw);

        GlShader vertex = null;
        GlShader fragment = null;
        try {
            // Modern packs (#version 130+ single-source, e.g. Complementary) use the compatibility stage normalizer;
            // the GLSL-120 Chocapic family (LIGHT) keeps the full 330-core rewrite. Detect off the fragment source.
            boolean modern = ModernPackTransformer.isModernSource(fshRaw);
            this.modernPack |= modern;
            // Scoped per pass, same as the terrain path: a pass listed in `impetus.iris.legacyPrograms` compiles
            // without IS_IRIS, and the one map drives both the DRAWBUFFERS parse and the injected prologue.
            Map<String, String> macros = com.bdmajora.impetus.iris.gl.shader.ShaderMacros.forProgram(
                    this.shaderDefines, source.getName());
            int[] drawBuffers = sanitizeCompositeDrawBuffers(source.getName(), DrawBuffers.parseActive(fshRaw, macros));
            String vsh;
            String fsh;
            if (modern) {
                // Modern sources rely on the driver preprocessor for their #if trees; the MC_*/IRIS_FEATURE_* macro
                // environment has to be present for those gates (colored lighting checks IRIS_FEATURE_CUSTOM_IMAGES).
                vsh = ModernPackTransformer.transform(stabilizeShaderSource(source.getName(),
                        com.bdmajora.impetus.iris.gl.shader.ShaderMacros.injectDefines(vshRaw, macros)));
                fsh = DrawBuffers.rewriteFragmentOutputs(ModernPackTransformer.transform(
                        stabilizeShaderSource(source.getName(),
                                com.bdmajora.impetus.iris.gl.shader.ShaderMacros.injectDefines(fshRaw, macros))),
                        drawBuffers);
            } else {
                // The macro environment has to be injected here too, not just on the modern branch. Without it a
                // legacy pack's whole post chain compiles with MC_VERSION, IRIS_VERSION, MC_RENDER_QUALITY,
                // MC_RENDER_STAGE_*, MC_OLD_LIGHTING and the IRIS_FEATURE_* flags all absent — while its gbuffer
                // programs, which go through ShaderProgramCompiler, get every one of them. The two halves of the
                // same pack then disagree about what version of Minecraft they are running on.
                //
                // It fails both ways round. Silently: an undefined name is 0 in a preprocessor expression, so
                // `#if MC_VERSION < 10800` is TRUE and every legacy pack took its pre-1.8 path through composite.
                // Loudly: a macro used as a value rather than a gate is a hard compile error, which is what killed
                // Pastel's final pass — `float mult = MC_RENDER_QUALITY * 0.0625;` in program/final.glsl, reported
                // as `final.fsh: 0(453): error C1503: undefined variable "MC_RENDER_QUALITY"`, dropping the pass to
                // a bare colortex0 blit.
                //
                // Injecting before the transform (as the modern branch does) is what makes this land correctly:
                // FullscreenTransformer.strip() drops the pack's own `#version` line, so the defines come out at the
                // top of the body and end up immediately after the generated `#version 330 core`.
                vsh = FullscreenTransformer.transformVertexShader(foldUncompilableConditionals(source.getName(),
                        com.bdmajora.impetus.iris.gl.shader.ShaderMacros.injectDefines(vshRaw, macros)));
                fsh = FullscreenTransformer.transformFragmentShader(foldUncompilableConditionals(source.getName(),
                        com.bdmajora.impetus.iris.gl.shader.ShaderMacros.injectDefines(fshRaw, macros)), drawBuffers);
            }
            IrisDebugDump.dumpText("src_" + source.getName() + ".vsh", vsh);
            IrisDebugDump.dumpText("src_" + source.getName() + ".fsh", fsh);
            vertex = new GlShader(ShaderType.VERTEX, source.getName() + ".vsh", vsh);
            fragment = new GlShader(ShaderType.FRAGMENT, source.getName() + ".fsh", fsh);

            ProgramBuilder builder = ProgramBuilder.begin(source.getName())
                    .attach(vertex)
                    .attach(fragment)
                    .bindAttributeLocation(FullscreenQuadRenderer.POSITION_SLOT, "a_Position")
                    .bindAttributeLocation(FullscreenQuadRenderer.TEXCOORD_SLOT, "a_TexCoord");
            if (!modern) {
                // The generated 330-core path writes to an explicit out array whose indices are the dense
                // draw-buffer slots, matching Iris's packed framebuffer attachments.
                builder.bindFragmentDataLocation(0, "iris_FragData");
            }
            GlProgram program = builder.link();

            assignSamplerUnits(program, stage);
            return new IrisProgram(program, drawBuffers);
        } finally {
            if (vertex != null) {
                vertex.destroy();
            }
            if (fragment != null) {
                fragment.destroy();
            }
        }
    }

    /**
     * Points every sampler uniform the program declares at its fixed texture unit (OptiFine's setProgramUniform1i),
     * with the pack's custom-texture overrides for the program's stage applied — a colortex override is skipped once
     * an earlier pass in the chain has written (flipped) that buffer, matching Iris's deactivation rule.
     */
    private void assignSamplerUnits(GlProgram program, TextureStage stage) {
        program.bind();
        assignSamplerUnits(program.getGlId(), samplerUnitsForStage(stage), mergedStageOverrides(stage),
                this.flippedAtLeastOnce);
        program.unbind();
    }

    /**
     * Assigns the standard sampler-unit mapping on the <em>currently bound</em> program (raw GL id), with the active
     * pipeline's gbuffers/shadow-stage custom-texture overrides. Used by the Impetus terrain and shadow overrides,
     * whose program objects are Impetus's rather than ours — both belong to the {@code gbuffers} texture stage.
     */
    public static void assignSamplerUnitsToBoundProgram(int programId) {
        assignSamplerUnits(programId, activeGbufferSamplerUnits, activeGbufferSamplerOverrides,
                java.util.Collections.<Integer>emptySet());
    }

    private static void assignSamplerUnits(int programId, Map<String, Integer> samplerUnits,
                                           Map<String, CustomTextureManager.Override> overrides,
                                           java.util.Set<Integer> flippedAtLeastOnce) {
        boolean waterShadowEnabled = LWJGL.glGetUniformLocation(programId, "watershadow") != -1;
        for (Map.Entry<String, Integer> entry : samplerUnits.entrySet()) {
            int location = LWJGL.glGetUniformLocation(programId, entry.getKey());
            if (location == -1) {
                continue;
            }
            int unit = entry.getValue();
            if (waterShadowEnabled && "shadow".equals(entry.getKey())) {
                // IrisSamplers.addShadowSamplers parity: when watershadow is present, the legacy shadow alias reads
                // the pre-translucent depth texture (shadowtex1), while watershadow reads shadowtex0.
                unit = isGbufferSamplerLayout(samplerUnits) ? GBUFFER_SHADOW_TEX_1_UNIT : SHADOW_TEX_1_UNIT;
            }
            CustomTextureManager.Override override = overrides.get(entry.getKey());
            if (override != null && (override.colorTarget < 0 || !flippedAtLeastOnce.contains(override.colorTarget))) {
                unit = override.unit;
            }
            LWJGL.glUniform1i(location, unit);
        }
        // Pack-declared sampler names with no standard unit (customTexture.<name> directives).
        for (Map.Entry<String, CustomTextureManager.Override> entry : overrides.entrySet()) {
            if (samplerUnits.containsKey(entry.getKey())) {
                continue;
            }
            int location = LWJGL.glGetUniformLocation(programId, entry.getKey());
            if (location != -1) {
                LWJGL.glUniform1i(location, entry.getValue().unit);
            }
        }
    }

    private static boolean isGbufferSamplerLayout(Map<String, Integer> samplerUnits) {
        return samplerUnits.getOrDefault("depthtex0", -1) == GBUFFER_DEPTH_TEX_0_UNIT;
    }

    /** The gbuffers-stage overrides flattened to name → unit, for {@link GbufferPrograms}' sampler table. */
    private Map<String, Integer> gbufferSamplerOverrideUnits() {
        Map<String, Integer> units = new LinkedHashMap<>();
        for (Map.Entry<String, CustomTextureManager.Override> entry
                : this.customTextureManager.getOverrides(TextureStage.GBUFFERS_AND_SHADOW).entrySet()) {
            units.put(entry.getKey(), entry.getValue().unit);
        }
        units.putAll(this.customImageManager.getUniformOverrides());
        return units;
    }

    private static Map<TextureStage, Map<String, Integer>> samplerUnitsByStage() {
        Map<TextureStage, Map<String, Integer>> byStage = new java.util.EnumMap<>(TextureStage.class);
        for (TextureStage stage : TextureStage.values()) {
            byStage.put(stage, samplerUnitsForStage(stage));
        }
        return byStage;
    }

    private static Map<String, Integer> samplerUnitsForStage(TextureStage stage) {
        return stage == TextureStage.GBUFFERS_AND_SHADOW ? GBUFFER_SAMPLER_UNITS : FULLSCREEN_SAMPLER_UNITS;
    }

    /**
     * The stage's custom-texture overrides plus the (stage-independent) custom-image uniform assignments, in the
     * Override form {@link #assignSamplerUnits} consumes. Image entries never deactivate (colorTarget -1).
     */
    private Map<String, CustomTextureManager.Override> mergedStageOverrides(TextureStage stage) {
        Map<String, CustomTextureManager.Override> merged =
                new LinkedHashMap<>(this.customTextureManager.getOverrides(stage));
        this.customImageManager.getUniformOverrides().forEach((name, unit) ->
                merged.put(name, new CustomTextureManager.Override(unit, -1)));
        this.renderTargetImageUnits.forEach((index, unit) ->
                merged.put("colorimg" + index, new CustomTextureManager.Override(unit, -1)));
        this.shadowColorImageUnits.forEach((index, unit) ->
                merged.put("shadowcolorimg" + index, new CustomTextureManager.Override(unit, -1)));
        return merged;
    }

    private static ProgramUniforms buildUniforms(String name, IrisProgram program) {
        ProgramUniforms.Builder builder = ProgramUniforms.builder(name, program.getProgram().getGlId());
        CommonUniforms.addCommonUniforms(builder);
        MatrixUniforms.addMatrixUniforms(builder);
        com.bdmajora.impetus.iris.uniforms.custom.ActiveCustomUniforms.assignTo(builder);
        return builder.buildUniforms();
    }

    /** The texture currently readable ("front") for each existing color target under the given flip state. */
    private int[] snapshotFrontTextures(BufferFlipper flipper) {
        int[] samplers = new int[IrisRenderTargets.MAX_COLOR_BUFFERS];
        for (int i = 0; i < samplers.length; i++) {
            samplers[i] = this.renderTargets.get(i) == null ? 0 : frontTexture(flipper, i);
        }
        return samplers;
    }

    private int frontTexture(BufferFlipper flipper, int index) {
        IrisRenderTarget target = this.renderTargets.getOrCreate(index);
        return flipper.isFlipped(index) ? target.getAltTexture() : target.getMainTexture();
    }

    private int frontTexture(BitSet flips, int index) {
        IrisRenderTarget target = this.renderTargets.getOrCreate(index);
        return flips.get(index) ? target.getAltTexture() : target.getMainTexture();
    }

    private int backTexture(BitSet flips, int index) {
        IrisRenderTarget target = this.renderTargets.getOrCreate(index);
        return flips.get(index) ? target.getMainTexture() : target.getAltTexture();
    }

    /**
     * Gbuffer-program draw buffers: shader packs address logical colortex indices, while the shared gbuffer FBO maps
     * those logical targets onto dense physical attachment points. Called by the terrain override and phase compiler.
     */
    public static int[] sanitizeDrawBuffers(String name, int[] drawBuffers) {
        return sanitizeDrawBuffers(name, drawBuffers, GBUFFER_ATTACHMENT_LIMIT);
    }

    /** Composite/deferred passes pack attachments densely, so any colortex0..15 index is fine. */
    private static int[] sanitizeCompositeDrawBuffers(String name, int[] drawBuffers) {
        return sanitizeDrawBuffers(name, drawBuffers, IrisRenderTargets.MAX_COLOR_BUFFERS);
    }

    private static int[] sanitizeDrawBuffers(String name, int[] drawBuffers, int maxExclusive) {
        return DrawBuffers.sanitize(drawBuffers, maxExclusive,
                buffer -> LOGGER.debug(
                        "[Iris] '{}' declares draw buffer {} (max {} here; usually an inactive #ifdef path); ignoring it",
                        name, buffer, maxExclusive - 1));
    }

    // ------------------------------------------------------------------ per-frame hooks

    private void runClearPasses(List<ClearPass> passes) {
        for (ClearPass pass : passes) {
            pass.framebuffer.bind();
            LWJGL.glViewport(0, 0,
                    pass.width > 0 ? pass.width : this.renderTargets.getWidth(),
                    pass.height > 0 ? pass.height : this.renderTargets.getHeight());
            float[] color = pass.color;
            if (color == null) {
                org.joml.Vector3f fog = CapturedRenderingState.INSTANCE.getFogColor();
                GlStateManager.clearColor(fog.x, fog.y, fog.z, 1.0f);
            } else {
                GlStateManager.clearColor(color[0], color[1], color[2], color[3]);
            }
            LWJGL.glClear(GL11.GL_COLOR_BUFFER_BIT);
        }
    }

    /**
     * renderWorld HEAD: redirect the frame into the gbuffer. Vanilla's own fog-colored clear inside
     * {@code renderWorldPass} then clears our attachments, and every world draw lands in the render targets.
     */
    public void beginWorldRendering(float partialTicks) {
        if (this.destroyed) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.displayWidth != this.renderTargets.getWidth() || mc.displayHeight != this.renderTargets.getHeight()) {
            this.renderTargets.resize(mc.displayWidth, mc.displayHeight);
            this.fullClearRequired = true;
            if (this.shaderStorageBuffers != null) {
                this.shaderStorageBuffers.onResize(mc.displayWidth, mc.displayHeight);
            }
            this.customImageManager.onResize(mc.displayWidth, mc.displayHeight);
        }

        if (this.shaderStorageBuffers != null && !this.shaderStorageBuffers.isEmpty()) {
            this.shaderStorageBuffers.bindAll();
        }

        SystemTimeUniforms.COUNTER.beginFrame(System.nanoTime());
        EyeBrightnessTracker.update();
        CapturedRenderingState.INSTANCE.setTickDelta(partialTicks);
        captureAtlasSize(mc);

        Entity camera = mc.getRenderViewEntity();
        if (camera != null) {
            double x = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
            double y = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
            double z = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;
            CapturedRenderingState.INSTANCE.setCameraPosition(x, y, z);
        }
        this.frameUpdateNotifier.onNewFrame();
        CommonUniforms.beginFrame();
        com.bdmajora.impetus.iris.uniforms.custom.ActiveCustomUniforms.update();

        // noisetex and the stub shadow maps ride along for the whole frame (gbuffer + fullscreen stages) on their
        // fixed units; vanilla never binds units above 1, and GlStateManager's 8-slot cache can't address them.
        // A pack-supplied texture.noise replaces the generated noise (Iris CustomTextureManager parity).
        bindNoiseTexture();
        bindOverlayTexture();
        GlTextureUnits.selectScratch(SHADOW_TEX_0_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.stubShadowMap.getTextureId());
        GlTextureUnits.selectScratch(SHADOW_TEX_1_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.stubShadowMap.getTextureId());
        GlTextureUnits.resetToUnit0();

        // Custom images: honor the pack-declared clears, then bind image units + paired samplers.
        this.customImageManager.clearAll();
        bindShaderPackResources();

        this.activeGbufferSamplerFlips = this.preTranslucentGbufferSamplerFlips;
        bindGbufferPbrSamplers();
        // colortex4..7 (gaux1..4) must be readable by gbuffer programs — MakeUp's terrain fog samples gaux4
        // (colortex7). Bind before world geometry renders; without it distant terrain fogs toward garbage and blows out.
        bindGbufferColorSamplers();

        IrisFramebuffer gbuffer = this.gbufferFramebuffer;
        this.currentGbuffer = gbuffer;
        runClearPasses(this.fullClearRequired ? this.fullClearPasses : this.clearPasses);
        this.fullClearRequired = false;
        // Iris beginLevelRendering: the setup computes run once (on the first frame the pipeline is used), then the
        // `begin` family runs every frame on the freshly cleared targets, before any geometry.
        if (!this.setupDispatched) {
            this.setupDispatched = true;
            if (!this.setupPasses.isEmpty()) {
                bindShaderPackResources(false);
                for (FullscreenPass pass : this.setupPasses) {
                    bindRenderTargetImages(pass);
                    dispatchComputes(pass.computes);
                }
                LWJGL.glUseProgram(0);
                LOGGER.info("[Iris] Dispatched {} setup pass(es)", this.setupPasses.size());
            }
        }
        runFullscreenFamily(this.beginPasses, mc);
        gbuffer.bind();
        drawGbufferBuffers(gbuffer, FIXED_FUNCTION_MASK);
        GlStateManager.disableBlend();
        disableIndexedBlend(GBUFFER_ATTACHMENT_LIMIT);
        if (this.skyAtFarPlane) {
            LWJGL.glDepthRange(0.0, 1.0);
            this.skyAtFarPlane = false;
        }
        this.worldRenderingActive = true;
    }

    /**
     * Switches the active gbuffer program for a fixed-function world-render phase (sky, entities, particles, weather,
     * clouds, hand — anchored on vanilla's profiler sections). Binds the pack's program for that phase and points the
     * gbuffer's draw-buffer mask at the program's {@code DRAWBUFFERS}; with no pack program the phase renders plain
     * fixed-function into {@code colortex0} only. Also (re)binds the gbuffer, which heals the redirection if something
     * (like the entity-outline framebuffer) rebound vanilla's framebuffer mid-frame.
     */
    /**
     * Sky phases render at the far plane: packs like LIGHT write BLACK from {@code gbuffers_skybasic} and repaint the
     * entire sky procedurally in composite, which only works if the vanilla sky dome (real geometry ~16 blocks above
     * the camera) never reaches {@code depthtex0} — otherwise composite classifies the dome as terrain and passes the
     * black through. Under OptiFine the sky never lands in the depth buffer; emulate that with glDepthRange(1,1),
     * which is immune to vanilla's own depthMask toggling inside renderSky.
     */
    private boolean skyAtFarPlane;

    /** The {@code alphaTest.<program>} override currently forced on the GL state, so it can be undone. */
    private com.bdmajora.impetus.iris.gl.blending.ProgramAlphaTest activeAlphaTest;

    /**
     * True when the pack supplies a program for {@code phase}, so a draw made in it lands in that program's
     * DRAWBUFFERS rather than in vanilla's fixed-function output. Callers that have to suppress a vanilla GL state
     * (blending into a packed gbuffer, say) use this to leave the fixed-function path untouched.
     */
    public boolean hasGbufferProgram(ProgramId phase) {
        return this.worldRenderingActive && this.gbufferPrograms != null && this.gbufferPrograms.get(phase) != null;
    }

    /**
     * {@return whether the pack ships this phase's program itself}, as opposed to the phase merely resolving through
     * OptiFine's fallback chain onto some other program that was never written with this geometry in mind.
     */
    public boolean hasDirectGbufferProgram(ProgramId phase) {
        return this.worldRenderingActive && this.gbufferPrograms != null && this.gbufferPrograms.hasDirect(phase);
    }

    public boolean isRenderingPostDeferredTranslucents() {
        return this.worldRenderingActive && !this.deferredPasses.isEmpty()
                && this.currentGbuffer == this.translucentGbufferFramebuffer;
    }

    /**
     * Iris's chain is {@code gbuffers_entities_translucent -> gbuffers_entities -> gbuffers_textured_lit}. Falling
     * straight through to {@code TexturedLit} skipped the middle link, so a pack shipping {@code gbuffers_entities}
     * but no {@code _translucent} variant (most of them) drew translucent entities with the generic textured program
     * and lost whatever the entity program does with {@code entityColor}, normals and diffuse lighting.
     */
    public ProgramId getTranslucentEntityPhase() {
        return hasGbufferProgram(ProgramId.EntitiesTrans) ? ProgramId.EntitiesTrans : ProgramId.Entities;
    }

    public void setPhase(ProgramId phase) {
        setPhase(phase, defaultRenderStage(phase));
    }

    /**
     * Iris's {@code WorldRenderingPhase} ordinals for the phases this pipeline can identify, published as the
     * {@code renderStage} uniform. Iris sets one for every draw; before this, only the hand and terrain paths did, so
     * every sky, cloud, weather, entity and outline draw reported {@code MC_RENDER_STAGE_NONE}. That is not a cosmetic
     * gap: Clarity's {@code gbuffers_skybasic} draws stars only under
     * {@code renderStage == MC_RENDER_STAGE_STARS}, so its star field could never appear — the star quads were painted
     * with the plain sky gradient instead.
     * <p>
     * Only the unambiguous phases are mapped; anything else reports {@code NONE} rather than guess at the pack.
     */
    private static int defaultRenderStage(ProgramId phase) {
        if (phase == null) {
            return 0; // MC_RENDER_STAGE_NONE
        }
        switch (phase) {
            case SkyBasic: return 1;    // MC_RENDER_STAGE_SKY
            case SkyTextured: return 4; // MC_RENDER_STAGE_SUN (vanilla draws sun then moon under one anchor)
            // Iris has no distinct phase for the eyes overlay; it draws inside the entity pass.
            case Entities: case SpiderEyes: return 11; // MC_RENDER_STAGE_ENTITIES
            case DamagedBlock: return 13; // MC_RENDER_STAGE_DESTROY
            case Line: return 14;       // MC_RENDER_STAGE_OUTLINE
            case Particles: return 19;  // MC_RENDER_STAGE_PARTICLES
            case Clouds: return 20;     // MC_RENDER_STAGE_CLOUDS
            case Weather: return 21;    // MC_RENDER_STAGE_RAIN_SNOW
            default: return 0;
        }
    }

    /** Overload for callers that know a finer phase than {@link ProgramId} can express (sky basic covers sky/stars/void). */
    public void setPhase(ProgramId phase, int renderStage) {
        if (!this.worldRenderingActive) {
            return;
        }
        this.currentPhase = phase;
        CapturedRenderingState.INSTANCE.setRenderStage(renderStage);
        // Every phase this switches to is vanilla fixed-function geometry (sky, entities, particles, block damage,
        // weather), submitted through client arrays that alias generic attribute slots. See
        // resetVanillaVertexArrayState: a leftover generic array wins over the aliased client array and flattens that
        // attribute to a constant.
        resetVanillaVertexArrayState();
        boolean sky = phase == ProgramId.SkyBasic || phase == ProgramId.SkyTextured;
        if (sky != this.skyAtFarPlane) {
            LWJGL.glDepthRange(sky ? 1.0 : 0.0, 1.0);
            this.skyAtFarPlane = sky;
        }
        GbufferPrograms.Entry entry = phase == null ? null : this.gbufferPrograms.get(phase);
        // The previous phase may have overridden the alpha test; put vanilla's back before deciding this phase's.
        if (this.activeAlphaTest != null) {
            this.activeAlphaTest.restore();
            this.activeAlphaTest = null;
        }
        if (entry == null) {
            LWJGL.glUseProgram(0);
            drawGbufferBuffers(this.currentGbuffer, FIXED_FUNCTION_MASK);
        } else {
            entry.getProgram().bind();
            bindShaderPackResources();
            int[] drawBuffers = DrawBuffers.sanitize(entry.getDrawBuffers(), GBUFFER_ATTACHMENT_LIMIT);
            // Re-assert colortex4..7 read bindings: sky/entity/hand phases sample gaux buffers too, and the prior
            // phase's fixed-function draws may have disturbed these units. If this program also writes a samplable
            // gbuffer target, bind the sampler to a copied scratch side so it never reads the active render target.
            bindGbufferColorSamplers(prepareGbufferFeedbackSamplers(drawBuffers));
            entry.getUniforms().update();
            drawGbufferBuffers(this.currentGbuffer, drawBuffers);
            entry.getBlendState().apply(drawBuffers);
            // alphaTest.<program>: packs that do their own discard turn the fixed-function test off entirely
            // (Photon), others tighten it to GREATER 0.0001 (Complementary). Held until the next setPhase.
            if (entry.getAlphaTest().hasDirectives()) {
                entry.getAlphaTest().apply();
                this.activeAlphaTest = entry.getAlphaTest();
            }
        }
    }

    /**
     * Re-uploads the current phase's {@code DYNAMIC} uniforms without repeating the rest of {@link #setPhase}'s state
     * work. A phase is selected once and then covers a whole batch of draws — {@code entities} is set once for every
     * entity in the frame — so anything that varies per draw inside a batch would otherwise never reach the GPU after
     * the phase began. {@code entityColor} is the case that needs it: the hurt flash belongs to one entity, not to the
     * batch.
     * <p>
     * No-op in the shadow pass, which binds its own entity program that this phase tracking does not describe.
     */
    public void refreshDynamicUniforms() {
        if (!this.worldRenderingActive || IrisShadowRenderer.isShadowPass() || this.currentPhase == null
                || this.gbufferPrograms == null) {
            return;
        }
        GbufferPrograms.Entry entry = this.gbufferPrograms.get(this.currentPhase);
        if (entry != null) {
            entry.getUniforms().update();
        }
    }

    /**
     * The "eyes" overlay layers — spider, enderman and ender dragon — which is what {@code gbuffers_spidereyes} is
     * for. OptiFine brackets the same three draws with {@code Shaders.beginSpiderEyes()}/{@code endSpiderEyes()};
     * Iris routes them through {@code ShaderKey.ENTITIES_EYES}.
     * <p>
     * The lightmap coordinate has to be rewritten here. 1.12 signals "full bright" for these layers by pushing the
     * raw sentinel {@code OpenGlHelper.setLightmapTextureCoords(unit, 61680, 0)}, which only works because
     * {@code GL_CLAMP} pins the lightmap <em>texture lookup</em> to the brightest texel. A shader consumes the same
     * value arithmetically — {@code gl_TextureMatrix[1] * gl_MultiTexCoord1} is {@code (61680 + 8) / 256 = 240.97},
     * i.e. 256x outside the [0,1] range every pack assumes. Packs raise that coordinate to a power (Mellow uses
     * {@code pow(lm.x, 2.4)}, so ~6e5), which overflows an {@code R11F_G11F_B10F} colortex and leaves an Inf/NaN
     * pixel wrapped in an enormous bloom halo. Modern Minecraft has no such sentinel — its full-bright packed light
     * is {@code 0xF000F0} — which is why Iris's {@code VanillaTransformer} substitutes a literal
     * {@code vec4(240.0, 240.0, 0.0, 1.0)} for {@code gl_MultiTexCoord1} on every {@code FULLBRIGHT} draw. Do the
     * same, from the GL side.
     * <p>
     * No-op in the shadow pass: Iris maps the eyes render type to the shadow entity program there, not to
     * {@code gbuffers_spidereyes}.
     */
    public void beginEyes() {
        if (!this.worldRenderingActive || IrisShadowRenderer.isShadowPass()) {
            return;
        }
        this.phaseBeforeEyes = this.currentPhase;
        this.phaseBeforeEyesStage = CapturedRenderingState.INSTANCE.getRenderStage();
        LWJGL.glMultiTexCoord2f(OpenGlHelper.lightmapTexUnit, FULL_BRIGHT_LIGHTMAP_COORD, FULL_BRIGHT_LIGHTMAP_COORD);
        setPhase(ProgramId.SpiderEyes);
    }

    /**
     * Back to the entity program, as OptiFine's {@code endSpiderEyes} does — except that the eyes layers also run in
     * the post-translucent entity batch, where the phase is {@code gbuffers_entities_translucent}. Restore what was
     * actually bound instead of assuming, so a pack with a dedicated translucent-entity program keeps it for the rest
     * of the batch. Vanilla puts the real lightmap coordinate back itself on the line after the model draw.
     */
    public void endEyes() {
        if (!this.worldRenderingActive || IrisShadowRenderer.isShadowPass()) {
            return;
        }
        setPhase(this.phaseBeforeEyes, this.phaseBeforeEyesStage);
    }

    /**
     * Routes the enchantment glint through {@code gbuffers_armor_glint}, matching OptiFine's
     * {@code ShadersRender.renderEnchantedGlintBegin} ({@code Shaders.useProgram(17)}).
     * <p>
     * Without this the glint inherits whichever program is current — {@code gbuffers_entities} for armour, the hand
     * program for a held item — so a pack that ships {@code gbuffers_armor_glint} to give the glint its own additive
     * treatment never gets it, and the glint is shaded as if it were the entity's own surface.
     * <p>
     * Two gates, both of which fall out of the existing state rather than needing the OptiFine-only
     * {@code renderItemGui} flag: {@code worldRenderingActive} is false while the GUI draws inventory items, and the
     * shadow pass maps every entity draw to the shadow program (OptiFine likewise skips the glint entirely there).
     */
    public void beginArmorGlint() {
        if (!this.worldRenderingActive || IrisShadowRenderer.isShadowPass()) {
            return;
        }
        this.phaseBeforeArmorGlint = this.currentPhase;
        this.phaseBeforeArmorGlintStage = CapturedRenderingState.INSTANCE.getRenderStage();
        this.armorGlintActive = true;
        setPhase(ProgramId.ArmorGlint);
    }

    /**
     * Restores whatever was bound before the glint, the same way {@link #endEyes()} does: the glint runs inside both
     * the entity batch and the first-person hand batch, so assuming {@code gbuffers_entities} would strand the hand.
     * OptiFine's {@code renderEnchantedGlintEnd} makes the same distinction explicitly.
     */
    public void endArmorGlint() {
        if (!this.armorGlintActive) {
            return;
        }
        this.armorGlintActive = false;
        setPhase(this.phaseBeforeArmorGlint, this.phaseBeforeArmorGlintStage);
    }

    /** The phase {@link #setPhase(ProgramId, int)} last selected, so {@link #endEyes()} can put it back. */
    private ProgramId currentPhase;
    private ProgramId phaseBeforeEyes;
    private int phaseBeforeEyesStage;
    private ProgramId phaseBeforeArmorGlint;
    private int phaseBeforeArmorGlintStage;
    /** Guards {@link #endArmorGlint()} so a begin that bailed out (GUI, shadow pass) cannot restore a stale phase. */
    private boolean armorGlintActive;

    /**
     * Port of OptiFine's {@code Shaders.drawHorizon} ({@code preSkyList}): draws an octagonal ring at the
     * render-distance edge, from ground level ({@code y = -cameraY}) up to {@code y = 16}, through the currently-bound
     * sky program ({@code gbuffers_skybasic}). Vanilla's {@code renderSky} only draws the sky disc (above the horizon)
     * and the void plane (well below it); the thin band at the horizon between the render-distance edge and those is
     * left uncovered. Because packs commonly set {@code colortex1} to not clear (OptiFine/Iris both honour that), those
     * uncovered pixels keep last frame's colortex1 — which self-perpetuated into the blown ~50 neutral horizon band.
     * skybasic derives the sky colour from the view direction (not vertex colour), so this fill gets the correct
     * atmospheric horizon colour and the band disappears. Call right before the sky disc, matching OptiFine.
     */
    public void drawSkyHorizon() {
        if (!this.worldRenderingActive || this.skyHorizonActive) {
            return;
        }
        this.skyHorizonActive = true;
        try {
            // Clamp the radius (Iris HorizonRenderer parity): at high render distances the ring would otherwise reach
            // the far plane and get clipped, letting the band return. 256 blocks is always beyond the visible terrain
            // fog yet safely inside the sky projection.
            float f = Math.min(Minecraft.getMinecraft().gameSettings.renderDistanceChunks * 16.0f, 256.0f);
            double d0 = f * 0.9238D;
            double d1 = f * 0.3826D;
            double d2 = -d1;
            double d3 = -d0;
            double top = 16.0D;
            double bottom = -CapturedRenderingState.INSTANCE.getCameraPosition().y;
            org.joml.Vector3f fog = CapturedRenderingState.INSTANCE.getFogColor();
            GlStateManager.color(fog.x, fog.y, fog.z);
            BufferBuilder bb = Tessellator.getInstance().getBuffer();
            bb.begin(7, DefaultVertexFormats.POSITION);
            bb.pos(d2, bottom, d3).endVertex();
            bb.pos(d2, top, d3).endVertex();
            bb.pos(d3, top, d2).endVertex();
            bb.pos(d3, bottom, d2).endVertex();
            bb.pos(d3, bottom, d2).endVertex();
            bb.pos(d3, top, d2).endVertex();
            bb.pos(d3, top, d1).endVertex();
            bb.pos(d3, bottom, d1).endVertex();
            bb.pos(d3, bottom, d1).endVertex();
            bb.pos(d3, top, d1).endVertex();
            bb.pos(d2, top, d1).endVertex();
            bb.pos(d2, bottom, d1).endVertex();
            bb.pos(d2, bottom, d1).endVertex();
            bb.pos(d2, top, d1).endVertex();
            bb.pos(d1, top, d0).endVertex();
            bb.pos(d1, bottom, d0).endVertex();
            bb.pos(d1, bottom, d0).endVertex();
            bb.pos(d1, top, d0).endVertex();
            bb.pos(d0, top, d1).endVertex();
            bb.pos(d0, bottom, d1).endVertex();
            bb.pos(d0, bottom, d1).endVertex();
            bb.pos(d0, top, d1).endVertex();
            bb.pos(d0, top, d2).endVertex();
            bb.pos(d0, bottom, d2).endVertex();
            bb.pos(d0, bottom, d2).endVertex();
            bb.pos(d0, top, d2).endVertex();
            bb.pos(d1, top, d3).endVertex();
            bb.pos(d1, bottom, d3).endVertex();
            bb.pos(d1, bottom, d3).endVertex();
            bb.pos(d1, top, d3).endVertex();
            bb.pos(d2, top, d3).endVertex();
            bb.pos(d2, bottom, d3).endVertex();
            Tessellator.getInstance().draw();
        } catch (Throwable t) {
            LOGGER.warn("[Iris] drawSkyHorizon failed: {}", t.toString());
        } finally {
            this.skyHorizonActive = false;
        }
    }

    private boolean skyHorizonActive;

    /**
     * Called when the Impetus terrain override program binds ({@code IrisTerrainShaderInterface.setupState}): points
     * the gbuffer's draw-buffer mask at the terrain/water program's {@code DRAWBUFFERS} directive.
     */
    public void onTerrainDraw(int[] drawBuffers, ProgramBlendState blendState) {
        onTerrainDraw(drawBuffers, blendState,
                com.bdmajora.impetus.iris.gl.blending.ProgramAlphaTest.empty(), false);
    }

    public void onTerrainDraw(int[] drawBuffers, ProgramBlendState blendState,
                              com.bdmajora.impetus.iris.gl.blending.ProgramAlphaTest alphaTest,
                              boolean translucentPass) {
        if (!this.worldRenderingActive) {
            return;
        }
        int[] sanitizedDrawBuffers = DrawBuffers.sanitize(drawBuffers, GBUFFER_ATTACHMENT_LIMIT);
        // The chunk renderer owns atlas/lightmap setup, but shader-pack samplers are Iris-owned dynamic bindings.
        // Re-assert them at program use, matching Iris' sampler model and OptiFine's repeated program-uniform/binding
        // setup, so gbuffers_water sees the current depthtex1/shadow/noise and a feedback-safe colortex4 snapshot.
        bindDepthSamplers();
        bindShadowSamplers();
        bindNoiseTexture();
        bindGbufferPbrSamplers();
        bindGbufferColorSamplers(prepareGbufferFeedbackSamplers(sanitizedDrawBuffers));
        drawGbufferBuffers(this.currentGbuffer, sanitizedDrawBuffers);
        if (translucentPass) {
            restoreGbufferTranslucentBlend(sanitizedDrawBuffers);
        } else {
            restoreGbufferOpaqueBlend(sanitizedDrawBuffers);
        }
        blendState.apply(sanitizedDrawBuffers);
        // The terrain passes set their own alpha test (vanilla uses GREATER 0.1 for the cutout layers); a pack
        // override replaces it for this draw. Recorded so the next setPhase puts vanilla's back.
        if (alphaTest.hasDirectives()) {
            alphaTest.apply();
            this.activeAlphaTest = alphaTest;
        }
    }

    private static void restoreGbufferOpaqueBlend(int[] drawBuffers) {
        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
        disableIndexedBlend(drawBuffers.length);
    }

    private static void restoreGbufferTranslucentBlend(int[] drawBuffers) {
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.depthMask(true);

        if (!LWJGL.supportsBufferBlending()) {
            return;
        }
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            LWJGL.glEnablei(GL11.GL_BLEND, slot);
            LWJGL.glBlendFuncSeparatei(slot, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ZERO);
        }
    }

    private static void disableIndexedBlend(int drawBufferSlots) {
        if (!LWJGL.supportsBufferBlending()) {
            return;
        }
        int maxSlots = Math.min(drawBufferSlots, LWJGL.glGetInteger(GL30.GL_MAX_DRAW_BUFFERS));
        for (int slot = 0; slot < maxSlots; slot++) {
            LWJGL.glDisablei(GL11.GL_BLEND, slot);
        }
    }

    public void afterTerrainDraw(int drawBufferSlots) {
        if (!this.worldRenderingActive || IrisShadowRenderer.isShadowPass()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        mc.getFramebuffer().bindFramebuffer(true);
        restoreMainDrawReadBuffers(mc);
        disableIndexedBlend(drawBufferSlots);
        GlTextureUnits.resetToUnit0();
    }

    /**
     * Called right after {@code setupCameraTransform}/{@code ActiveRenderInfo.updateRenderInfo}: vanilla has just read
     * the camera matrices back into {@code ActiveRenderInfo}'s buffers; copy them for the uniform providers.
     */
    public void captureRenderingState() {
        if (!this.worldRenderingActive) {
            return;
        }
        CapturedRenderingState.INSTANCE.setGbufferModelView(new Matrix4f(ActiveRenderInfoAccessor.getModelViewMatrix()));
        CapturedRenderingState.INSTANCE.setGbufferProjection(new Matrix4f(ActiveRenderInfoAccessor.getProjectionMatrix()));
    }

    /**
     * Renders the shadow map for this frame. Called right after {@link #captureRenderingState} (the shadow angle and
     * camera position are current, nothing has drawn into the gbuffer yet), then re-points GL at the gbuffer and puts
     * the fresh shadow map on the {@code shadowtex0/1} units, replacing the always-lit stub bound at frame start.
     * <p>
     * The {@code prepare} family runs here too, matching Iris's {@code renderShadows}: it is the first thing after the
     * shadow map, so its passes can read {@code shadowtex}/{@code shadowcolor} (Photon's {@code prepare} bakes the
     * cloud shadow map into colortex8 that terrain lighting then samples) and still land before any gbuffer geometry.
     */
    public void renderShadowMap() {
        if (!this.worldRenderingActive) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (this.prepareBeforeShadow) {
            runFullscreenFamily(this.preparePasses, mc);
        }

        if (this.shadowRenderer != null) {
            this.shadowRenderer.render();
            dispatchComputePasses();

            this.currentGbuffer.bind();
            LWJGL.glViewport(0, 0, this.renderTargets.getWidth(), this.renderTargets.getHeight());

            bindShadowSamplers();
        }

        if (!this.prepareBeforeShadow) {
            runFullscreenFamily(this.preparePasses, mc);
        }
    }

    /**
     * Called at the {@code "translucent"} profiler anchor — after all opaque world content (terrain, entities,
     * particles, weather), before the translucent block layer. Snapshots the pre-translucent depth ({@code depthtex1})
     * and runs the pack's {@code deferred} chain, then points world rendering at the post-deferred gbuffer.
     */
    public void beginTranslucents() {
        if (!this.worldRenderingActive) {
            return;
        }
        copyDepthTexture(this.renderTargets.getDepthTextureNoTranslucents());

        Minecraft mc = Minecraft.getMinecraft();

        if (!this.deferredPasses.isEmpty()) {
            GlStateManager.disableBlend();
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableAlpha();

            bindDepthSamplers();
            // Re-establish the custom image/sampler bindings (Iris binds images at every program use; the shadow
            // pass's out-of-band FF/TESR rendering may have disturbed the high texture units).
            bindShaderPackResources();
            for (FullscreenPass pass : this.deferredPasses) {
                runPass(pass, mc);
            }
            LWJGL.glUseProgram(0);
            restoreTextureUnits();
            this.activeGbufferSamplerFlips = this.translucentGbufferSamplerFlips;
            bindDepthSamplers();
            bindShadowSamplers();
            bindNoiseTexture();
            bindShaderPackResources();
            bindGbufferPbrSamplers();
            bindGbufferColorSamplers();
            GlStateManager.enableDepth();
            GlStateManager.enableAlpha();

            // The rest of the world (translucents, hand) renders into the post-deferred front textures.
            this.currentGbuffer = this.translucentGbufferFramebuffer;
            this.translucentGbufferFramebuffer.bind();
            LWJGL.glViewport(0, 0, this.renderTargets.getWidth(), this.renderTargets.getHeight());
        }

        // OptiFine's Shaders.beginWater() contract (EntityRenderer.java:1870, Shaders.java:4484): the translucent
        // terrain draws with BLENDING ON and DEPTH WRITES ON. Vanilla itself just set depthMask(false) for this
        // section, but shader water must land in depthtex0 — the composites find water surfaces by comparing
        // depthtex0 against the pre-translucent depthtex1 copied above. Blend must also be (re)enabled here: the
        // deferred chain above runs blend-off, and unlike modern MC there is no RenderType state setup to restore it.
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.depthMask(true);
    }

    /** Called right before the solid hand draws: snapshot the pre-hand depth ({@code depthtex2}). */
    public void beginHand() {
        if (!this.worldRenderingActive) {
            return;
        }
        copyDepthTexture(this.renderTargets.getDepthTextureNoHand());
    }

    /**
     * Starts the first-person hand gbuffer pass. Matching OptiFine's {@code ShadersRender.renderHand0} (and Iris's
     * {@code HandRenderer.renderSolid}), the solid hand draws into the <em>pre-deferred</em> gbuffer, before
     * {@link #beginTranslucents()} runs the pack's {@code deferred} chain. That is what lights the hand: deferred
     * packs (e.g. Complementary) write only albedo/normal/lightmap data in {@code gbuffers_hand} and do all shading
     * in {@code deferred*} — a hand drawn after that chain stays as raw unlit gbuffer data. When the pack has no
     * hand program, the hand still renders into colortex0 fixed-function.
     *
     * @return true when hand rendering should proceed and {@link #endHandRendering()} must be called
     */
    public boolean beginHandRendering() {
        return beginHandRendering(ProgramId.Hand, 16); // MC_RENDER_STAGE_HAND_SOLID
    }

    /**
     * Starts OptiFine's late hand pass ({@code renderHand1}) after translucent world geometry has drawn but before the
     * composite/final chain consumes the gbuffer. This keeps nearby translucent collision panes from being blended over
     * the first-person hand until it appears to vanish.
     */
    public boolean beginHandTranslucentRendering() {
        return beginHandRendering(ProgramId.HandWater, 23); // MC_RENDER_STAGE_HAND_TRANSLUCENT
    }

    private boolean beginHandRendering(ProgramId programId, int renderStage) {
        if (this.destroyed || !this.worldRenderingActive) {
            return false;
        }
        this.currentGbuffer.bind();
        LWJGL.glViewport(0, 0, this.renderTargets.getWidth(), this.renderTargets.getHeight());
        LWJGL.glDepthRange(0.0, 1.0);
        this.skyAtFarPlane = false;

        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        // The hand is drawn into an already-populated world depth buffer. When the camera is pressed into collision
        // geometry, even the squeezed OptiFine hand projection can fail LEQUAL and vanish; force it to win only for
        // this pass, then restore LEQUAL in endHandRendering before translucent/world drawing resumes.
        GlStateManager.depthFunc(GL11.GL_ALWAYS);
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        logHandVertexStateProbe();
        resyncTextureUnitZero();
        resetVanillaVertexArrayState();

        GbufferPrograms.Entry entry = this.gbufferPrograms != null ? this.gbufferPrograms.get(programId) : null;
        int packedLight = getHandPackedLight();
        setupHandLightmap(packedLight);
        bindGbufferPbrSamplers();
        CapturedRenderingState.INSTANCE.setRenderStage(renderStage);
        if (entry == null) {
            LWJGL.glUseProgram(0);
            drawGbufferBuffers(this.currentGbuffer, FIXED_FUNCTION_MASK);
            resyncTextureUnitZero();
            return true;
        }
        entry.getProgram().bind();
        int[] drawBuffers = DrawBuffers.sanitize(entry.getDrawBuffers(), GBUFFER_ATTACHMENT_LIMIT);
        drawGbufferBuffers(this.currentGbuffer, drawBuffers);
        entry.getBlendState().apply(drawBuffers);
        entry.setHandLightmap(getBlockLightmapCoord(packedLight), getSkyLightmapCoord(packedLight));
        bindShaderPackResources();
        entry.getUniforms().update();
        resyncTextureUnitZero();
        return true;
    }

    /**
     * Hands texture unit 0 back to vanilla in a state its caches can be trusted about, right before the first-person
     * hand draws.
     * <p>
     * {@code ItemRenderer.renderArmFirstPerson} binds the player skin with
     * {@code TextureManager.bindTexture(getLocationSkin())}, which bottoms out in {@code GlStateManager.bindTexture} —
     * guarded by <em>both</em> a cached active-unit index and a cached per-unit texture name. The pipeline has to
     * select scratch/sampler units with raw {@code glActiveTexture} + {@code glBindTexture} (shadow mipmaps, depth
     * copies, custom textures and images), which those caches never see. Once the cache and GL disagree about unit 0,
     * vanilla's skin bind silently no-ops and the arm samples whatever unit 0 really holds — the block atlas. That is
     * exactly the reported symptom: a flat lime arm inside an opaque orange box, because the skin's second
     * ("jacket") layer is fully transparent and would have been discarded, while atlas texels are opaque and never are.
     * <p>
     * Bouncing through another unit defeats the active-unit cache, and clearing the binding defeats the texture-name
     * cache, so the very next {@code bindTexture} call is guaranteed to reach GL.
     */
    /**
     * Hands the fixed-function vertex pipeline back to vanilla in a state it can actually use, before the
     * first-person arm draws.
     * <p>
     * Vanilla submits the arm through client arrays ({@code glVertexPointer}/{@code glTexCoordPointer} +
     * {@code glEnableClientState}, see {@code ForgeHooksClient.preDraw}). On the compatibility profile those alias
     * generic attribute slots — 0 is {@code gl_Vertex}, 2 {@code gl_Normal}, 3 {@code gl_Color}, 8..15
     * {@code gl_MultiTexCoord0..7} — the same aliasing {@link FullscreenQuadRenderer} relies on deliberately. When
     * a generic array and the aliased conventional array are both enabled for a slot, <em>the generic array wins</em>,
     * and every vertex then reads a single value: the attribute goes constant.
     * <p>
     * That is the measured hand bug. {@code gl_MultiTexCoord0} was constant across the whole draw, so the arm and its
     * second ("jacket") layer each sampled one texel — a flat green arm inside a flat shell, the shell visible only
     * because a constant UV lands on an opaque texel instead of the transparent overlay it should have hit and been
     * discarded for. Position, normal and colour survived because nothing had left <em>their</em> slots enabled.
     * <p>
     * Leftover state here poisons the session, not just the frame: {@code ModelRenderer} bakes the arm into a display
     * list on first render and {@code glDrawArrays} dereferences the arrays at compile time, so one bad compile is
     * baked in for good. This runs before the first arm draw, so that compile happens clean too.
     */
    public static void resetVanillaVertexArrayState() {
        LWJGL.glBindVertexArray(0);
        LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        LWJGL.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        for (int slot = 0; slot < VANILLA_ALIASED_ATTRIBUTE_SLOTS; slot++) {
            LWJGL.glDisableVertexAttribArray(slot);
        }
    }

    /** gl_Vertex/gl_Normal/gl_Color/gl_MultiTexCoord0..7 all alias generic slots below this. */
    private static final int VANILLA_ALIASED_ATTRIBUTE_SLOTS = 16;

    /**
     * One-shot record of the vertex-array state the vanilla hand draw was about to inherit, so a single run says
     * whether {@link #resetVanillaVertexArrayState()} treated the real disease. A non-zero {@code vao} or
     * {@code arrayBuffer}, or any {@code enabledAttribs} entry — slot 8 ({@code gl_MultiTexCoord0}) above all — is it.
     */
    private static void logHandVertexStateProbe() {
        if (handVertexStateProbeLogged) {
            return;
        }
        handVertexStateProbeLogged = true;
        StringBuilder enabled = new StringBuilder();
        for (int slot = 0; slot < VANILLA_ALIASED_ATTRIBUTE_SLOTS; slot++) {
            if (LWJGL.glGetVertexAttribi(slot, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED) != 0) {
                enabled.append(enabled.length() == 0 ? "" : ",").append(slot);
            }
        }
        LOGGER.info("[Iris] Hand vertex-state probe: vao={} arrayBuffer={} clientActiveTexture={} enabledAttribs=[{}]",
                LWJGL.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                LWJGL.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),
                LWJGL.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0,
                enabled);
    }

    private static boolean handVertexStateProbeLogged;

    /**
     * One-shot identification of the texture the first-person arm actually sampled. Called straight after vanilla's
     * arm draw, while unit 0 still holds whatever that draw used, because the shader-side probe showed the sampled
     * alpha is 1.0 where the player skin's jacket layer is fully transparent — so the arm's overlay box can never be
     * discarded and covers the real arm. Dimensions identify the texture without guesswork: 64x64 is a player skin,
     * 256+ is the block atlas.
     */
    public static void logHandBoundTextureProbe() {
        if (handBoundTextureProbeLogged) {
            return;
        }
        handBoundTextureProbeLogged = true;
        Minecraft mc = Minecraft.getMinecraft();
        GlTextureUnits.resetToUnit0();
        int bound = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int width = LWJGL.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = LWJGL.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        int format = LWJGL.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        // A sampler object bound here would override the texture's own sampling state, which is the last thing that
        // could explain an alpha of 1.0 coming from an RGBA texture that genuinely has alpha 0 there. Non-zero is it.
        int sampler = LWJGL.glGetInteger(GL33.GL_SAMPLER_BINDING);
        int skin = mc.player != null ? textureIdOf(mc.player.getLocationSkin()) : -1;
        int atlas = textureIdOf(net.minecraft.client.renderer.texture.TextureMap.LOCATION_BLOCKS_TEXTURE);
        LOGGER.info("[Iris] Hand bound-texture probe: unit0={} ({}x{}, internalFormat=0x{}, sampler={}); "
                + "playerSkin={}, blockAtlas={}", bound, width, height, Integer.toHexString(format), sampler,
                skin, atlas);
    }

    private static int textureIdOf(net.minecraft.util.ResourceLocation location) {
        net.minecraft.client.renderer.texture.ITextureObject texture =
                Minecraft.getMinecraft().getTextureManager().getTexture(location);
        return texture != null ? texture.getGlTextureId() : -1;
    }

    private static boolean handBoundTextureProbeLogged;

    private static void resyncTextureUnitZero() {
        // resetToUnit0 already performs the step-through-unit-1 dance that used to be inlined on the two lines above
        // it, and the cached unbind below leaves real GL and the cache both holding 0 — so the trailing raw
        // glBindTexture was redundant too. One coherent pair is the whole job.
        GlTextureUnits.resetToUnit0();
        GlStateManager.bindTexture(0);
    }

    public void endHandRendering() {
        LWJGL.glUseProgram(0);
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        CapturedRenderingState.INSTANCE.setRenderStage(0); // MC_RENDER_STAGE_NONE
    }

    /**
     * Feeds the first-person hand its lightmap coordinate. BSL's {@code gbuffers_hand} derives all its
     * brightness from {@code lmCoord = gl_TextureMatrix[1] * gl_MultiTexCoord1} (it never samples the lightmap texture),
     * while Sodium/Embeddium can leave the fixed-function lightmap coord stale. Set both the legacy current texcoord
     * and the hand shader bridge uniform to the player's combined light, matching Iris
     * ({@code getPackedLightCoords(player)}). The vanilla lightmap texture matrix (scale 1/256, translate 8/256)
     * expects the raw [0,240] block/sky values {@code getCombinedLight} packs.
     */
    private void setupHandLightmap(int packedLight) {
        float blockLight = getBlockLightmapCoord(packedLight);
        float skyLight = getSkyLightmapCoord(packedLight);
        LWJGL.glMultiTexCoord2f(OpenGlHelper.lightmapTexUnit, blockLight, skyLight);
        setupLightmapTextureMatrix();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static int getHandPackedLight() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            return FULL_BRIGHT_LIGHTMAP;
        }
        BlockPos eyePos = new BlockPos(mc.player.posX, mc.player.posY + mc.player.getEyeHeight(), mc.player.posZ);
        return mc.world.getCombinedLight(eyePos, 0);
    }

    private static float getBlockLightmapCoord(int packedLight) {
        return packedLight & 0xFFFF;
    }

    private static float getSkyLightmapCoord(int packedLight) {
        return (packedLight >>> 16) & 0xFFFF;
    }

    private static void setupLightmapTextureMatrix() {
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.matrixMode(GL_TEXTURE_MODE);
        GlStateManager.loadIdentity();
        GlStateManager.translate(LIGHTMAP_TEXTURE_OFFSET, LIGHTMAP_TEXTURE_OFFSET, LIGHTMAP_TEXTURE_OFFSET);
        GlStateManager.scale(LIGHTMAP_TEXTURE_SCALE, LIGHTMAP_TEXTURE_SCALE, LIGHTMAP_TEXTURE_SCALE);
        GlStateManager.matrixMode(GL_MODELVIEW_MODE);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    /** renderWorld RETURN: run the composite chain and final pass, then hand a clean GL state back to vanilla. */
    public void finishWorldRendering() {
        if (!this.worldRenderingActive) {
            return;
        }
        this.worldRenderingActive = false;
        if (this.glErrorProbeFrames > 0) {
            this.glErrorProbeFrames--;
        }
        // An alphaTest.<program> override must not survive into the composite chain or vanilla's GUI pass.
        if (this.activeAlphaTest != null) {
            this.activeAlphaTest.restore();
            this.activeAlphaTest = null;
        }
        Minecraft mc = Minecraft.getMinecraft();

        // Full-screen passes draw with depth/blend/alpha-test off. Going through GlStateManager keeps its state cache
        // coherent with reality, so vanilla's later enable/disable calls are not silently skipped.
        GlStateManager.disableBlend();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableAlpha();

        bindDepthSamplers();

        // Off unless -Dimpetus.iris.sunProbeFrame is set. Reads the celestial uniforms at exactly the point the
        // composite/final passes consume them.
        com.bdmajora.impetus.iris.devtool.SunProbe.sample();

        // centerDepthSmooth: sample depthtex0 at the screen centre now that all geometry (translucents included) has
        // landed in it, before any composite consumes the uniform — OptiFine's readCenterDepth in renderHand1.
        this.centerDepthSampler.sample(this.renderTargets.getDepthTexture().getTextureId(),
                this.renderTargets.getWidth(), this.renderTargets.getHeight(),
                SystemTimeUniforms.COUNTER.getLastFrameTime(), this.centerDepthHalfLife);

        for (FullscreenPass pass : this.passes) {
            runPass(pass, mc);
        }

        if (this.blitSourceFramebuffer != null) {
            this.blitSourceFramebuffer.bindAsReadBuffer();
            int target = OpenGlHelper.isFramebufferEnabled() ? mc.getFramebuffer().framebufferObject : 0;
            LWJGL.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target);
            LWJGL.glBlitFramebuffer(0, 0, this.renderTargets.getWidth(), this.renderTargets.getHeight(),
                    0, 0, mc.displayWidth, mc.displayHeight,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        }

        // Optional wide-gamut output conversion: runs in place on the presentation target as the last color op
        // of the shader frame. No-op (and zero cost) when the configured colorspace is sRGB.
        if (this.colorSpaceConverter.isActive()) {
            bindMainRenderTarget(mc);
            restoreMainDrawReadBuffers(mc);
            this.colorSpaceConverter.run(mc.displayWidth, mc.displayHeight, this.quadRenderer);
        }

        resetRenderTargetMipmaps();

        // Iris FinalPassRenderer.SwapPass: buffers the chain leaves odd-flipped carry this frame's data on their
        // ALT side — copy it back to MAIN so next frame's baked FBOs and sampler snapshots read fresh data. NB:
        // glCopyTexSubImage2D reads the GL_READ_BUFFER of the framebuffer bound to GL_FRAMEBUFFER (bind(), not
        // bindAsReadBuffer() — Iris hit TAA breakage on many drivers with the read-framebuffer binding).
        if (!this.swapPasses.isEmpty()) {
            // glCopyTexSubImage2D needs the destination bound to a texture unit. Binding it on whatever unit happens
            // to be selected — unit 0 in practice, since bindColorSamplers ends there — would rewrite a cached slot
            // behind GlStateManager's back, after which its next cached bind of the value it still believes is there
            // no-ops and the unit keeps this texture. Do the copy on a scratch unit no cached slot describes.
            GlTextureUnits.selectScratch(DEPTH_COPY_SCRATCH_UNIT);
            try {
                for (SwapPass swap : this.swapPasses) {
                    swap.from.bind();
                    LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, swap.targetTexture);
                    LWJGL.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, swap.width, swap.height);
                }
                LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            } finally {
                GlTextureUnits.releaseScratch();
            }
        }
        // Hand control back to vanilla: its framebuffer bound, no program/VAO, texture units cleaned up.
        LWJGL.glUseProgram(0);
        bindMainRenderTarget(mc);
        restoreMainDrawReadBuffers(mc);
        restoreTextureUnits();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();

    }

    public boolean isWorldRenderingActive() {
        return this.worldRenderingActive;
    }

    /** {@code rain.depth} — true when the pack wants rain and snow to write depth. */
    public boolean shouldWriteRainAndSnowToDepthBuffer() {
        return this.rainDepth;
    }

    /** {@code beacon.beam.depth} — true when the pack wants the beacon beam in depthtex. */
    public boolean shouldWriteBeaconBeamToDepthBuffer() {
        return this.beaconBeamDepth;
    }

    /** {@code frustum.culling = false} — the pack needs off-screen geometry drawn (Iris shouldDisableFrustumCulling). */
    public boolean shouldDisableFrustumCulling() {
        return !this.frustumCulling;
    }

    /** {@code occlusion.culling = false} — the pack needs occluded geometry drawn. */
    public boolean shouldDisableOcclusionCulling() {
        return !this.occlusionCulling;
    }

    /** {@code skipAllRendering} — suppress all world geometry; the composite chain still runs. */
    public boolean skipAllRendering() {
        return this.skipAllRendering;
    }

    /** {@code separateEntityDraws} — entities are drawn in their own pass after the deferred chain. */
    public boolean shouldSeparateEntityDraws() {
        return this.separateEntityDraws;
    }

    /** {@code particles.ordering} — where particles fall relative to the deferred chain. */
    public String getParticleOrdering() {
        return this.particleOrdering;
    }

    /**
     * {@code backFace.<layer>} — false when the pack wants that terrain layer's back faces drawn.
     *
     * @param layerOrdinal {@link net.minecraft.util.BlockRenderLayer#ordinal()}
     */
    public boolean shouldCullBackFaces(int layerOrdinal) {
        return layerOrdinal < 0 || layerOrdinal >= this.backFaceCulling.length
                || this.backFaceCulling[layerOrdinal];
    }

    public boolean shouldDisableVanillaEntityShadows() {
        return this.shadowRenderer != null;
    }

    /**
     * Compiles the pack's shadowcomp compute passes ({@code .csh}, Iris extension — Complementary's floodfill light
     * propagation). Each family index becomes a compute-only pass carrying the flip snapshot of its place in the
     * chain, so its {@code colortexN} reads and {@code colorimgN} writes hit the same sides the rest of the frame does.
     */
    private void buildComputePasses(ShaderPack pack, BufferFlipper flipper) {
        for (int i = 0; i < ProgramArrayId.ShadowComposite.getNumPrograms(); i++) {
            Optional<ProgramSource> source = pack.getProgramSet().get(ProgramArrayId.ShadowComposite, i);
            if (!source.isPresent()) {
                continue;
            }
            List<ComputePass> computes = buildFamilyComputePasses(pack, source.get(), TextureStage.SHADOWCOMP);
            // A shadowcomp entry with a vertex+fragment pair draws a full-screen quad into shadowcolor0/1
            // (Iris ShadowCompositeRenderer). None of the packs in circulation ship one — every shadowcomp in the
            // wild is compute-only — but the family is scheduled here so one that does is not silently dropped.
            FullscreenPass raster = source.get().hasRasterStages()
                    ? buildShadowCompositePass(pack, source.get(), computes)
                    : null;
            if (raster != null) {
                this.shadowCompPasses.add(raster);
            } else if (!computes.isEmpty()) {
                this.shadowCompPasses.add(FullscreenPass.computeOnly(source.get().getName(),
                        snapshotFrontTextures(flipper), flipper.snapshot(), computes));
            }
        }
    }

    /**
     * Builds a raster {@code shadowcomp} pass: a full-screen quad into the shadow pass's own colour attachments, at
     * shadow-map resolution. Unlike the numbered colour families these targets do not ping-pong, so the pass carries
     * no flip state.
     */
    private FullscreenPass buildShadowCompositePass(ShaderPack pack, ProgramSource source, List<ComputePass> computes) {
        if (this.shadowRenderer == null) {
            LOGGER.warn("[Iris] '{}' draws into shadowcolor but the pack declares no shadow program; skipping",
                    source.getName());
            return null;
        }
        String name = source.getName();
        try {
            IrisProgram program = cachedProgram(source);
            if (program == null) {
                return null;
            }
            int[] drawBuffers = sanitizeShadowCompositeDrawBuffers(name, program.getDrawBuffers());
            IrisFramebuffer framebuffer = new IrisFramebuffer();
            for (int i = 0; i < drawBuffers.length; i++) {
                framebuffer.addColorAttachment(drawBuffers[i], i, drawBuffers[i] == 0
                        ? this.shadowRenderer.getColorTextureId()
                        : this.shadowRenderer.getColorTexture1Id());
            }
            checkFramebufferComplete(framebuffer, "shadowcomp", drawBuffers);

            FullscreenPass pass = new FullscreenPass(name, program, this.compiledUniforms.get(name), framebuffer,
                    snapshotFrontTextures(this.renderTargets.getBufferFlipper()), drawBuffers,
                    ProgramBlendState.from(pack.getProperties(), name),
                    new BitSet(), new BitSet(), new BitSet(), computes);
            pass.viewportWidth = this.shadowRenderer.getResolution();
            pass.viewportHeight = this.shadowRenderer.getResolution();
            LOGGER.info("[Iris] Scheduled shadowcomp pass '{}': drawBuffers={}, {}x{}",
                    name, Arrays.toString(drawBuffers), pass.viewportWidth, pass.viewportHeight);
            return pass;
        } catch (Exception e) {
            LOGGER.error("[Iris] Failed to build shadowcomp pass '{}'; it will be skipped: {}", name, e.getMessage());
            return null;
        }
    }

    /** Only shadowcolor0/1 exist, so any higher index a shadowcomp DRAWBUFFERS names has nothing to attach to. */
    private static int[] sanitizeShadowCompositeDrawBuffers(String name, int[] drawBuffers) {
        int[] sanitized = new int[drawBuffers.length];
        int count = 0;
        for (int buffer : drawBuffers) {
            if (buffer > 1) {
                LOGGER.warn("[Iris] '{}' writes shadowcolor{}, but only shadowcolor0/1 exist; dropping it",
                        name, buffer);
                continue;
            }
            sanitized[count++] = buffer;
        }
        return count == 0 ? new int[]{0} : Arrays.copyOf(sanitized, count);
    }

    /**
     * Compiles every compute stage attached to one program — the unsuffixed {@code <name>.csh} plus the letter-suffixed
     * {@code <name>_a.csh} .. {@code _z.csh} Iris extension. Dispatch size follows Iris's priority order: the
     * {@code indirect} directive, then {@code const ivec3 workGroups}, then {@code const vec2 workGroupsRender}, then
     * one invocation per pixel of the render target.
     */
    private List<ComputePass> buildFamilyComputePasses(ShaderPack pack, ProgramSource source, TextureStage stage) {
        String[] computeSources = source.getComputeSources();
        if (computeSources.length == 0) {
            return java.util.Collections.emptyList();
        }
        if (!isProgramEnabled(pack, source.getName())) {
            LOGGER.info("[Iris] Skipping disabled compute pass '{}'", source.getName());
            return java.util.Collections.emptyList();
        }

        // The voxel-volume dispatch fallback is a shadowcomp-only workaround (Complementary's floodfill under-declares
        // its groups). Iris never second-guesses a declared `workGroups`, and doing so elsewhere is actively wrong:
        // Photon's deferred4_a.csh declares ivec3(1,1,1) with local_size_x=256 and does one parallel reduction into
        // colorimg4, so scaling it to a 64x64x64 LPV volume would run it 4096 times over the same texels.
        int[] volume = stage == TextureStage.SHADOWCOMP ? this.customImageManager.getFirst3DImageSize() : null;
        List<ComputePass> built = new ArrayList<>();
        for (int variant = 0; variant < computeSources.length; variant++) {
            if (computeSources[variant] == null) {
                continue;
            }
            String name = ProgramSource.computeVariantName(source.getName(), variant);
            try {
                String csh = com.bdmajora.impetus.iris.gl.shader.ShaderMacros.injectDefines(
                        CustomTextureTransformer.transform(name, computeSources[variant], stage),
                        this.shaderDefines);
                csh = stabilizeShaderSource(name, csh);
                IrisDebugDump.dumpText("src_" + name + ".csh", csh);
                int[] localSize = parseLocalSize(csh);
                int[] fallbackWorkGroups = (volume != null && localSize != null)
                        ? new int[]{
                                ceilDiv(volume[0], localSize[0]),
                                ceilDiv(volume[1], localSize[1]),
                                ceilDiv(volume[2], localSize[2])
                        }
                        : null;
                float[] renderScale = parseWorkGroupsRender(csh, this.shaderDefines);
                long[] indirectPointer = this.indirectDispatchPointers.get(name);
                int indirectBuffer = -1;
                long indirectOffset = 0L;
                if (indirectPointer != null && this.shaderStorageBuffers != null) {
                    indirectBuffer = this.shaderStorageBuffers.getBufferId((int) indirectPointer[0]);
                    indirectOffset = indirectPointer[1];
                    if (indirectBuffer == -1) {
                        LOGGER.warn("[Iris] Compute pass '{}' requests indirect dispatch from undeclared bufferObject.{}",
                                name, indirectPointer[0]);
                    }
                }

                int[] workGroups = parseWorkGroups(csh, this.shaderDefines);
                if (workGroups != null && fallbackWorkGroups != null
                        && !coversVolume(workGroups, localSize, volume)) {
                    LOGGER.warn("[Iris] Compute pass '{}' declared dispatch {}x{}x{} does not cover custom image volume {}x{}x{} with local {}x{}x{}; using {}x{}x{}",
                            name,
                            workGroups[0], workGroups[1], workGroups[2],
                            volume[0], volume[1], volume[2],
                            localSize[0], localSize[1], localSize[2],
                            fallbackWorkGroups[0], fallbackWorkGroups[1], fallbackWorkGroups[2]);
                    workGroups = fallbackWorkGroups;
                }
                if (workGroups == null) {
                    if (fallbackWorkGroups != null) {
                        workGroups = fallbackWorkGroups;
                    } else if (renderScale == null && indirectBuffer == -1) {
                        // Iris ComputeProgram.getWorkGroups' last resort: cover the screen, one invocation per pixel.
                        renderScale = new float[]{1.0f, 1.0f};
                        workGroups = new int[]{1, 1, 1};
                        LOGGER.info("[Iris] Compute pass '{}' declares no workGroups/workGroupsRender/indirect; dispatching over the full render size",
                                name);
                    } else {
                        workGroups = new int[]{1, 1, 1};
                    }
                }
                GlShader shader = new GlShader(ShaderType.COMPUTE, name + ".csh", csh);
                GlProgram program;
                try {
                    program = ProgramBuilder.begin(name).attach(shader).link();
                } finally {
                    shader.destroy();
                }
                program.bind();
                assignSamplerUnits(program.getGlId(), FULLSCREEN_SAMPLER_UNITS,
                        mergedStageOverrides(stage), this.flippedAtLeastOnce);
                program.unbind();
                ProgramUniforms.Builder uniforms = ProgramUniforms.builder(name, program.getGlId());
                CommonUniforms.addCommonUniforms(uniforms);
                MatrixUniforms.addMatrixUniforms(uniforms);
                com.bdmajora.impetus.iris.uniforms.custom.ActiveCustomUniforms.assignTo(uniforms);
                built.add(new ComputePass(name, program, uniforms.buildUniforms(),
                        workGroups[0], workGroups[1], workGroups[2],
                        renderScale != null ? renderScale[0] : Float.NaN,
                        renderScale != null ? renderScale[1] : Float.NaN,
                        localSize != null ? localSize[0] : 1,
                        localSize != null ? localSize[1] : 1,
                        indirectBuffer, indirectOffset));
                LOGGER.info("[Iris] Compute pass '{}' ready: dispatch {}x{}x{}{}",
                        name, workGroups[0], workGroups[1], workGroups[2],
                        localSize == null ? "" : " (local " + localSize[0] + "x" + localSize[1] + "x" + localSize[2] + ")");
            } catch (Exception e) {
                LOGGER.error("[Iris] Failed to build compute pass '{}'; it will be skipped: {}", name, e.getMessage());
            }
        }
        return built;
    }

    private static boolean isProgramEnabled(ShaderPack pack, String programName) {
        return pack.getProperties().getProgramEnabled(programName).orElse(Boolean.TRUE);
    }

    private static final Pattern UNINITIALIZED_LIGHT_VOLUME =
            Pattern.compile("(?m)^([\\t ]*)vec4\\s+lightVolume\\s*;[\\t ]*$");
    /**
     * Source stabilizer for a hazard proven by the shader pins: Complementary's {@code GetComplexLightVolume} can
     * accumulate into an uninitialized {@code vec4}, and zero-init is required under our transformed sources.
     * <p>
     * A companion rewrite used to flatten the pack's {@code fract(d + goldenRatio * mod(float(frameCounter), 3600.0))}
     * dither reroll into a no-op {@code fract(d)}, dating from the era when block-edge shimmer was being chased. That
     * root cause turned out to be the zeroed {@code at_midBlock} attribute, and the rewrite outlived it — while
     * silently breaking every raymarch that depends on the reroll. Complementary's light shafts take only ~15 samples
     * over the whole ray and rely on a per-frame dither offset for TAA to converge them; frozen, the sample planes
     * become static screen-space slabs of lit fog that cut straight across terrain. Iris never rewrites pack source
     * this way, and {@link com.bdmajora.impetus.iris.uniforms.SystemTimeUniforms} advances {@code frameCounter}
     * per frame exactly as Iris does, so the pack's own reroll is left to run as authored.
     */
    /** {@code #version <number>} — the first one wins, matching the driver preprocessor. */
    private static final Pattern VERSION_DIRECTIVE = Pattern.compile("(?m)^\\s*#version\\s+(\\d+)");

    /**
     * The {@code ARB_shader_texture_lod} entry points. In a {@code #version 130+} shader these are only legal when the
     * pack enabled the extension itself; the core {@code textureGrad} family replaces them, which is the rename Iris
     * does in {@code CommonTransformer}. Just Colored Lighting and Sildur's call {@code texture2DGradARB} from
     * {@code #version 430 compatibility} sources, which the NVIDIA driver rejects outright (error C7531).
     */
    private static final Map<String, String> ARB_TEXTURE_LOD_FUNCTIONS;

    static {
        Map<String, String> arb = new LinkedHashMap<>();
        arb.put("texture2DGradARB", "textureGrad");
        arb.put("texture3DGradARB", "textureGrad");
        arb.put("textureCubeGradARB", "textureGrad");
        arb.put("texture2DLodARB", "textureLod");
        arb.put("texture2DProjGradARB", "textureProjGrad");
        arb.put("shadow2DGradARB", "textureGrad");
        ARB_TEXTURE_LOD_FUNCTIONS = java.util.Collections.unmodifiableMap(arb);
    }

    /**
     * Rewrites the {@code *ARB} texture-lookup entry points to their core equivalents, but only for sources that
     * declare {@code #version 130} or newer. A GLSL 120 pack that calls them has to enable the extension itself and
     * {@code textureGrad} would not exist there, so those are left exactly as authored.
     */
    private static String normalizeArbTextureLookups(String name, String source) {
        Matcher version = VERSION_DIRECTIVE.matcher(source);
        if (!version.find()) {
            return source;
        }
        int versionNumber;
        try {
            versionNumber = Integer.parseInt(version.group(1));
        } catch (NumberFormatException e) {
            return source;
        }
        if (versionNumber < 130) {
            return source;
        }

        String result = source;
        for (Map.Entry<String, String> entry : ARB_TEXTURE_LOD_FUNCTIONS.entrySet()) {
            if (!result.contains(entry.getKey())) {
                continue;
            }
            result = result.replaceAll("(?<![A-Za-z0-9_])" + Pattern.quote(entry.getKey()) + "(?=\\s*\\()",
                    Matcher.quoteReplacement(entry.getValue()));
            LOGGER.info("[Iris] Program '{}': {} -> {} (core replacement for #version {})",
                    name, entry.getKey(), entry.getValue(), versionNumber);
        }
        return result;
    }

    /**
     * Resolves the {@code #if} directives the driver's preprocessor cannot legally accept — float comparisons, and
     * expressions that are outright malformed — leaving everything else for the driver.
     * <p>
     * Split out of {@link #stabilizeShaderSource} because the two legacy paths need exactly this and none of the rest:
     * both hand a {@code #version 120} source to a 330 rewrite, where {@code normalizeArbTextureLookups} is a no-op by
     * construction. Every caller must inline the macro environment as {@code #define} lines first, which is what makes
     * the fold self-contained.
     * <p>
     * Callers, all three of which reach the driver by a different route and each of which had to be fixed separately:
     * {@link #stabilizeShaderSource} (gbuffer programs, via {@code ShaderProgramCompiler}), the legacy fullscreen
     * composite/deferred/final path in this class, and
     * {@link com.bdmajora.impetus.iris.terrain.IrisTerrainProgramOverride} (terrain and, critically, the translucent
     * water pass).
     */
    public static String foldUncompilableConditionals(String name, String source) {
        String folded = GlslPreprocessor.foldFloatConditionals(source, java.util.Collections.emptyMap());
        if (!folded.equals(source)) {
            LOGGER.info("[Iris] Program '{}': folded #if conditional(s) the GLSL preprocessor cannot parse", name);
        }
        return folded;
    }

    public static String stabilizeShaderSource(String name, String source) {
        source = foldUncompilableConditionals(name, source);
        source = normalizeArbTextureLookups(name, source);
        source = com.bdmajora.impetus.iris.terrain.GlslIntegerOverloadPolyfill.widenIntegerBuiltinCalls(name, source);
        Matcher declaration = UNINITIALIZED_LIGHT_VOLUME.matcher(source);
        if (declaration.find()) {
            LOGGER.info("[Iris] Program '{}': zero-initializing colored-lighting volume accumulator (pack declares it uninitialized)",
                    name);
            source = declaration.replaceAll("$1vec4 lightVolume = vec4(0.0);");
        }
        return source;
    }

    private static int[] parseWorkGroups(String source, Map<String, String> defines) {
        String active = preprocessActiveShaderSource(source, defines);
        int[] workGroups = parseWorkGroupsDirect(active);
        return workGroups != null ? workGroups : parseWorkGroupsDirect(source);
    }

    /** {@return the `const vec2 workGroupsRender` scale factors, or {@code null} when not declared} */
    private static float[] parseWorkGroupsRender(String source, Map<String, String> defines) {
        String active = preprocessActiveShaderSource(source, defines);
        float[] scale = parseWorkGroupsRenderDirect(active);
        return scale != null ? scale : parseWorkGroupsRenderDirect(source);
    }

    private static float[] parseWorkGroupsRenderDirect(String source) {
        Matcher matcher = Pattern.compile(
                "const\\s+vec2\\s+workGroupsRender\\s*=\\s*vec2\\s*\\(([^)]*)\\)")
                .matcher(stripGlslComments(source));
        if (!matcher.find()) {
            return null;
        }
        try {
            String[] values = matcher.group(1).split(",");
            float x = Float.parseFloat(values[0].trim().replace("f", ""));
            float y = values.length > 1 ? Float.parseFloat(values[1].trim().replace("f", "")) : x;
            return new float[]{x, y};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Parses every {@code indirect.<pass> = <bufferObjectIndex> <offsetBytes>} directive (Iris syntax). */
    private static Map<String, long[]> parseIndirectPointers(Map<String, String> rawProperties) {
        Map<String, long[]> pointers = new LinkedHashMap<>();
        rawProperties.forEach((key, value) -> {
            if (!key.startsWith("indirect.")) {
                return;
            }
            try {
                String[] parts = value.trim().split("\\s+");
                pointers.put(key.substring("indirect.".length()),
                        new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])});
            } catch (RuntimeException e) {
                LOGGER.warn("[Iris] Malformed indirect directive '{} = {}'", key, value);
            }
        });
        return pointers;
    }

    /**
     * Delegates to {@link GlslPreprocessor#resolveConditionals}, which additionally falls back to the raw source when
     * resolution yields nothing — an unterminated {@code #if} otherwise swallows the rest of the file and the scan sees
     * no directives at all. Kept as a named seam because the compute-directive callers pass their own define maps.
     */
    private static String preprocessActiveShaderSource(String source, Map<String, String> defines) {
        return GlslPreprocessor.resolveConditionals(source, defines);
    }

    private static int[] parseWorkGroupsDirect(String source) {
        String stripped = stripGlslComments(source);
        Matcher vector = Pattern.compile(
                "const\\s+(?:u?ivec|vec)([234])\\s+workGroups\\s*=\\s*(?:u?ivec|vec)\\d\\s*\\(([^)]*)\\)")
                .matcher(stripped);
        if (vector.find()) {
            int dimensions = Integer.parseInt(vector.group(1));
            String[] values = vector.group(2).split(",");
            if (values.length == dimensions || values.length == 1) {
                int[] groups = {1, 1, 1};
                for (int i = 0; i < Math.min(3, dimensions); i++) {
                    Integer value = parsePositiveInt(values.length == 1 ? values[0] : values[i]);
                    if (value == null) {
                        return null;
                    }
                    groups[i] = value;
                }
                return groups;
            }
        }

        int x = parseNamedWorkGroup(stripped, "X");
        int y = parseNamedWorkGroup(stripped, "Y");
        int z = parseNamedWorkGroup(stripped, "Z");
        if (x > 0 || y > 0 || z > 0) {
            return new int[]{Math.max(1, x), Math.max(1, y), Math.max(1, z)};
        }
        return null;
    }

    private static boolean coversVolume(int[] workGroups, int[] localSize, int[] volume) {
        return workGroups[0] * localSize[0] >= volume[0]
                && workGroups[1] * localSize[1] >= volume[1]
                && workGroups[2] * localSize[2] >= volume[2];
    }

    private static int[] parseLocalSize(String source) {
        Matcher matcher = Pattern.compile(
                "local_size_x\\s*=\\s*(\\d+)(?:\\s*,\\s*local_size_y\\s*=\\s*(\\d+))?(?:\\s*,\\s*local_size_z\\s*=\\s*(\\d+))?")
                .matcher(stripGlslComments(source));
        if (!matcher.find()) {
            return null;
        }
        int x = Integer.parseInt(matcher.group(1));
        int y = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 1;
        int z = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 1;
        return new int[]{x, y, z};
    }

    private static int parseNamedWorkGroup(String source, String axis) {
        Matcher matcher = Pattern.compile("const\\s+int\\s+workGroups" + axis + "\\s*=\\s*(\\d+)\\s*;")
                .matcher(source);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static Integer parsePositiveInt(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int ceilDiv(int value, int divisor) {
        return Math.max(1, (value + divisor - 1) / divisor);
    }

    private static String stripGlslComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /**
     * Runs the shadowcomp compute chain: a full barrier makes the shadow pass's imageStore voxelization visible,
     * each pass dispatches, and a closing barrier publishes the results to every later sampler read.
     */
    private void dispatchComputePasses() {
        if (this.shadowCompPasses.isEmpty()) {
            return;
        }
        // Iris rebinds all images + paired samplers at every compute use (ComputeProgram.use -> images.update()). The
        // Impetus shadow-terrain draw that just voxelized runs through managed code that can reset texture/image
        // units, so re-establish the voxel/floodfill bindings here rather than trusting the frame-start bindAll to
        // survive it — otherwise the compute could read/write the wrong (or unbound) volume.
        bindShaderPackResources(false);
        LWJGL.glMemoryBarrier(com.bdmajora.impetus.lwjgl.GL42.GL_ALL_BARRIER_BITS);
        boolean drewRaster = false;
        for (FullscreenPass pass : this.shadowCompPasses) {
            // NB: only the images, not bindColorSamplers(pass), for the compute-only entries. This runs between the
            // shadow map and the gbuffers, and colortex0..7 would land on texture units 0..7 — the block atlas and
            // lightmap units the world is about to render with. restoreTextureUnits() deliberately leaves the low
            // units alone, so there would be nothing to undo it. Image units are a separate namespace, so safe.
            if (pass.program == null) {
                bindRenderTargetImages(pass);
                dispatchComputes(pass.computes);
            } else {
                // A raster shadowcomp draws into shadowcolor. runPass dispatches this pass's own computes first,
                // so they must not be dispatched again here. It needs the colortex samplers, so the gbuffer
                // bindings are restored right after the loop.
                runPass(pass, Minecraft.getMinecraft());
                drewRaster = true;
            }
        }
        LWJGL.glUseProgram(0);
        if (drewRaster) {
            restoreTextureUnits();
            bindDepthSamplers();
            bindNoiseTexture();
            bindShaderPackResources();
            bindGbufferPbrSamplers();
            bindGbufferColorSamplers();
        }
    }

    /**
     * Dispatches a list of compute programs in order, publishing each one's writes with a full barrier before the next
     * runs (Complementary's floodfill iterations read the previous one's output, and a family pass's fragment stage
     * reads its computes' output).
     */
    private void dispatchComputes(List<ComputePass> computes) {
        for (ComputePass pass : computes) {
            pass.program.bind();
            bindShaderPackResources(false);
            pass.uniforms.update();
            if (pass.indirectBuffer != -1) {
                // Indirect dispatch: group counts read from the pack-declared SSBO at the given offset.
                LWJGL.glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, pass.indirectBuffer);
                LWJGL.glDispatchComputeIndirect(pass.indirectOffset);
                LWJGL.glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, 0);
            } else if (!Float.isNaN(pass.renderScaleX)) {
                // Screen-relative dispatch (`const vec2 workGroupsRender`): recomputed every frame from the
                // current render size and the shader's local_size.
                int groupsX = Math.max(1, ceilDiv((int) Math.ceil(this.renderTargets.getWidth() * pass.renderScaleX), pass.localSizeX));
                int groupsY = Math.max(1, ceilDiv((int) Math.ceil(this.renderTargets.getHeight() * pass.renderScaleY), pass.localSizeY));
                LWJGL.glDispatchCompute(groupsX, groupsY, 1);
            } else {
                LWJGL.glDispatchCompute(pass.groupsX, pass.groupsY, pass.groupsZ);
            }
            // Each floodfill iteration reads the previous one's writes, so by default every dispatch is fenced.
            // `allowConcurrentCompute` is the pack asserting its dispatches are independent.
            if (!this.allowConcurrentCompute) {
                LWJGL.glMemoryBarrier(com.bdmajora.impetus.lwjgl.GL42.GL_ALL_BARRIER_BITS);
            }
        }
        if (this.allowConcurrentCompute) {
            // Still publish the whole group's writes before anything samples them.
            LWJGL.glMemoryBarrier(com.bdmajora.impetus.lwjgl.GL42.GL_ALL_BARRIER_BITS);
        }
    }

    private static void destroyFamilyComputes(List<FullscreenPass> family) {
        for (FullscreenPass pass : family) {
            for (ComputePass compute : pass.computes) {
                compute.program.destroy();
            }
        }
    }

    /** Captures the block-atlas dimensions for the {@code atlasSize}/{@code terrainTextureSize} uniforms. */
    private static void captureAtlasSize(Minecraft mc) {
        net.minecraft.client.renderer.texture.ITextureObject atlas =
                mc.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureMap.LOCATION_BLOCKS_TEXTURE);
        if (atlas == null) {
            return;
        }
        int previousTexture = bindScratchTexture2D(atlas.getGlTextureId());
        try {
            int width = LWJGL.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = LWJGL.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            CapturedRenderingState.INSTANCE.setAtlasSize(width, height);
        } finally {
            restoreScratchTexture2D(previousTexture);
        }
    }

    // ------------------------------------------------------------------ state helpers

    /**
     * Runs a numbered family that executes in the middle of world rendering ({@code begin}, {@code prepare}): the
     * quads draw with depth/blend/alpha-test off, then the gbuffer state the world expects is put back. The composite
     * and deferred chains do this inline because they also switch flip snapshots and gbuffer framebuffers.
     */
    private void runFullscreenFamily(List<FullscreenPass> family, Minecraft mc) {
        if (family.isEmpty()) {
            return;
        }
        GlStateManager.disableBlend();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableAlpha();

        bindDepthSamplers();
        bindShaderPackResources();
        for (FullscreenPass pass : family) {
            runPass(pass, mc);
        }

        LWJGL.glUseProgram(0);
        restoreTextureUnits();
        bindDepthSamplers();
        bindShadowSamplers();
        bindNoiseTexture();
        bindShaderPackResources();
        bindGbufferPbrSamplers();
        bindGbufferColorSamplers();
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();
        GlStateManager.depthMask(true);

        if (this.currentGbuffer != null) {
            this.currentGbuffer.bind();
            LWJGL.glViewport(0, 0, this.renderTargets.getWidth(), this.renderTargets.getHeight());
        }
    }

    /** Runs one full-screen pass into its framebuffer (or Minecraft's framebuffer for the final pass). */
    private void runPass(FullscreenPass pass, Minecraft mc) {
        // Iris CompositeRenderer.renderAll: the pass's computes dispatch first, under this pass's flip state, then a
        // barrier publishes their writes to the draw that follows (deferred4 texelFetches the SH that deferred4_a
        // just imageStored into colortex4). Both the colortex samplers they read and the colorimg images they write
        // have to be established before dispatching.
        if (!pass.computes.isEmpty()) {
            bindColorSamplers(pass);
            bindRenderTargetImages(pass);
            dispatchComputes(pass.computes);
            LWJGL.glUseProgram(0);
        }
        if (pass.program == null) {
            return;
        }
        if (pass.framebuffer != null) {
            pass.framebuffer.bind();
            // A pass writing an explicitly-sized buffer draws at that buffer's resolution, not the screen's.
            LWJGL.glViewport(0, 0,
                    pass.viewportWidth > 0 ? pass.viewportWidth : this.renderTargets.getWidth(),
                    pass.viewportHeight > 0 ? pass.viewportHeight : this.renderTargets.getHeight());
        } else {
            bindMainRenderTarget(mc);
            restoreMainDrawReadBuffers(mc);
        }
        GlStateManager.disableBlend();
        disableIndexedBlend(pass.drawBuffers.length);
        pass.blendState.apply(pass.drawBuffers);
        setupMipmappedBuffers(pass);
        bindColorSamplers(pass);
        pass.program.bind();
        bindShaderPackResources();
        pass.uniforms.update();
        boolean probe = this.glErrorProbeFrames > 0;
        if (probe) {
            drainGlError(); // clear anything vanilla/Impetus left so we only attribute this pass's own errors
        }
        if (this.modernPack) {
            // The [0,1] fullscreen quad maps to NDC via an ortho projection (modelview/texture stay identity), so
            // ftransform() = projection*modelview*gl_Vertex = ortho*[0,1] = NDC and (gl_TextureMatrix[0]*
            // gl_MultiTexCoord0) passes the [0,1] texcoords through. Save/restore so the hand and GUI that vanilla
            // draws after the composite chain are unaffected.
            pushFullscreenFixedFunctionMatrices();
            if (probe) {
                reportGlError(pass.name + " push-matrices");
            }
            this.quadRenderer.draw();
            if (probe) {
                reportGlError(pass.name + " draw");
            }
            popFixedFunctionMatrices();
            if (probe) {
                reportGlError(pass.name + " pop-matrices");
            }
        } else {
            this.quadRenderer.draw();
            if (probe) {
                reportGlError(pass.name + " draw");
            }
        }
    }

    private void setupMipmappedBuffers(FullscreenPass pass) {
        if (pass.mipmappedBuffers.nextSetBit(0) < 0) {
            return;
        }
        GlTextureUnits.selectScratch(MIPMAP_SCRATCH_UNIT);
        for (int index = pass.mipmappedBuffers.nextSetBit(0); index >= 0;
             index = pass.mipmappedBuffers.nextSetBit(index + 1)) {
            IrisRenderTarget target = this.renderTargets.get(index);
            if (target != null) {
                target.generateMipmaps(pass.flipsBefore.get(index));
            }
        }
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GlTextureUnits.resetToUnit0();
    }

    private void resetRenderTargetMipmaps() {
        GlTextureUnits.selectScratch(MIPMAP_SCRATCH_UNIT);
        for (int i = 0; i < IrisRenderTargets.MAX_COLOR_BUFFERS; i++) {
            IrisRenderTarget target = this.renderTargets.get(i);
            if (target != null) {
                target.resetMipmaps();
            }
        }
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GlTextureUnits.resetToUnit0();
    }

    public static void drainGlError() {
        while (LWJGL.glGetError() != 0) {
            // discard
        }
    }

    public static void reportGlError(String where) {
        int error = LWJGL.glGetError();
        if (error != 0) {
            LOGGER.warn("[Iris] GL error 0x{} ({}) at: {}",
                    Integer.toHexString(error), error, where);
        }
    }

    // Fixed-function matrix modes (GL_MODELVIEW/PROJECTION/TEXTURE); not in the core GL wrapper we use elsewhere.
    private static final int GL_MODELVIEW_MODE = 0x1700;
    private static final int GL_PROJECTION_MODE = 0x1701;
    private static final int GL_TEXTURE_MODE = 0x1702;

    private static void pushFullscreenFixedFunctionMatrices() {
        // Projection is the ortho that maps the [0,1] fullscreen quad to NDC [-1,1] (matching Iris's composite
        // gl_ProjectionMatrix), so `gl_Position = ftransform()` (Complementary/BSL) resolves to
        // projection*modelview*gl_Vertex = ortho*[0,1] = NDC. Modelview + texture stay identity.
        GlStateManager.matrixMode(GL_PROJECTION_MODE);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0, 1.0, 0.0, 1.0, -1.0, 1.0);
        GlStateManager.matrixMode(GL_TEXTURE_MODE);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.matrixMode(GL_MODELVIEW_MODE);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
    }

    private static void popFixedFunctionMatrices() {
        GlStateManager.matrixMode(GL_PROJECTION_MODE);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL_TEXTURE_MODE);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL_MODELVIEW_MODE);
        GlStateManager.popMatrix();
    }

    /**
     * Copies the depth of the active gbuffer framebuffer into {@code destination} — how OptiFine snapshots
     * {@code depthtex1}/{@code depthtex2}. Runs on a scratch texture unit so no vanilla-tracked binding is disturbed.
     */
    private void copyDepthTexture(DepthTexture destination) {
        // Iris/OptiFine copy from the shader framebuffer's depth attachment. Do not rely on whatever framebuffer a
        // previous vanilla hook, hand render, or post pass happened to leave bound.
        if (this.currentGbuffer != null) {
            this.currentGbuffer.bind();
        }
        int previousTexture = bindScratchTexture2D(destination.getTextureId());
        try {
            LWJGL.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0,
                    this.renderTargets.getWidth(), this.renderTargets.getHeight());
        } finally {
            restoreScratchTexture2D(previousTexture);
        }
        bindShaderPackResources();
    }

    private static int bindScratchTexture2D(int texture) {
        GlTextureUnits.selectScratch(DEPTH_COPY_SCRATCH_UNIT);
        int previousTexture = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        return previousTexture;
    }

    private static void restoreScratchTexture2D(int texture) {
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GlTextureUnits.resetToUnit0();
    }

    private static void bindMainRenderTarget(Minecraft mc) {
        if (OpenGlHelper.isFramebufferEnabled()) {
            mc.getFramebuffer().bindFramebuffer(true);
        } else {
            LWJGL.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            LWJGL.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
        }
    }

    private static void restoreMainDrawReadBuffers(Minecraft mc) {
        if (OpenGlHelper.isFramebufferEnabled()) {
            LWJGL.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
            LWJGL.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        } else {
            LWJGL.glDrawBuffers(GL_BACK_BUFFER);
            LWJGL.glReadBuffer(GL_BACK_BUFFER);
        }
    }

    private void bindColorSamplers(FullscreenPass pass) {
        for (int i = IrisRenderTargets.MAX_COLOR_BUFFERS - 1; i >= 0; i--) {
            if (pass.colorSamplers[i] != 0) {
                LWJGL.glBindSampler(i, 0);
                if (i < 8) {
                    // Units 0..7 go through GlStateManager so vanilla's texture-unit cache stays coherent.
                    GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + i);
                    GlStateManager.bindTexture(pass.colorSamplers[i]);
                } else {
                    // colortex8..15 live beyond GlStateManager's cache (indexing it there throws); vanilla never
                    // touches these units, so a raw bind is correct — the tail below hands the selector back.
                    GlTextureUnits.selectScratch(i);
                    LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, pass.colorSamplers[i]);
                }
            }
        }
        // Deliberately not a bare setActiveTexture(GL_TEXTURE0). If the loop took the raw branch above, real GL is on
        // a high unit while the cache still reads 0 — and setActiveTexture would then swallow the return as a no-op,
        // stranding the selector there for every later cached bind. resetToUnit0 steps through unit 1 so it issues.
        GlTextureUnits.resetToUnit0();
    }

    /**
     * Binds the {@code colorimgN} images for one pass. Iris's binding is flip-aware, so a compute writing
     * {@code colorimg4} hits the exact texture the following programs sample as {@code colortex4}.
     */
    private void bindRenderTargetImages(FullscreenPass pass) {
        if (this.renderTargetImageUnits.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, Integer> entry : this.renderTargetImageUnits.entrySet()) {
            IrisRenderTarget target = this.renderTargets.get(entry.getKey());
            if (target == null) {
                continue;
            }
            int texture = pass.flipsBefore.get(entry.getKey()) ? target.getAltTexture() : target.getMainTexture();
            LWJGL.glBindImageTexture(entry.getValue(), texture, 0, false, 0, GL15.GL_READ_WRITE,
                    target.getInternalFormat().getInternalFormat());
        }
        bindShadowColorImages();
    }

    /**
     * Binds {@code shadowcolorimg0/1} over the shadow pass's colour attachments. They do not ping-pong, so unlike the
     * render-target images there is no flip state to follow.
     */
    private void bindShadowColorImages() {
        if (this.shadowColorImageUnits.isEmpty() || this.shadowRenderer == null) {
            return;
        }
        for (Map.Entry<Integer, Integer> entry : this.shadowColorImageUnits.entrySet()) {
            int texture = entry.getKey() == 0
                    ? this.shadowRenderer.getColorTextureId()
                    : this.shadowRenderer.getColorTexture1Id();
            if (texture == 0) {
                continue;
            }
            LWJGL.glBindImageTexture(entry.getValue(), texture, 0, false, 0, GL15.GL_READ_WRITE,
                    IrisShadowRenderer.SHADOW_COLOR_INTERNAL_FORMAT);
        }
    }

    private void bindDepthSamplers() {
        // Bind both the high fullscreen units and the OptiFine 1.12 gbuffers units (6/12).
        bindDepthSampler(DEPTH_TEX_0_UNIT, this.renderTargets.getDepthTexture());
        bindDepthSampler(DEPTH_TEX_1_UNIT, this.renderTargets.getDepthTextureNoTranslucents());
        bindDepthSampler(DEPTH_TEX_2_UNIT, this.renderTargets.getDepthTextureNoHand());
        bindDepthSampler(GBUFFER_DEPTH_TEX_0_UNIT, this.renderTargets.getDepthTexture());
        bindDepthSampler(GBUFFER_DEPTH_TEX_1_UNIT, this.renderTargets.getDepthTextureNoTranslucents());
        GlTextureUnits.resetToUnit0();
    }

    private void bindGbufferPbrSamplers() {
        // PBR maps on the gbuffer-stage normals/specular units (2/3, through GlStateManager so its cache stays
        // coherent). Fullscreen passes overwrite these units with colortex2/3; rebind before later gbuffers stages
        // such as water and hand sample the atlas again.
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + 2);
        GlStateManager.bindTexture(com.bdmajora.impetus.iris.pbr.PBRAtlasManager.getNormalsAtlas(
                this.defaultNormals.getTextureId()));
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + 3);
        GlStateManager.bindTexture(com.bdmajora.impetus.iris.pbr.PBRAtlasManager.getSpecularAtlas(
                this.defaultSpecular.getTextureId()));
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    private static void bindDepthSampler(int unit, DepthTexture texture) {
        LWJGL.glBindSampler(unit, 0);
        bindTextureUnit(unit, texture.getTextureId());
    }

    /** {@code iris_overlay} is constant for the whole frame; bound alongside the other frame-long samplers. */
    private void bindOverlayTexture() {
        bindTextureUnit(OVERLAY_TEX_UNIT, this.noOverlayTexture.getTextureId());
        bindTextureUnit(GBUFFER_OVERLAY_UNIT, this.noOverlayTexture.getTextureId());
        GlTextureUnits.resetToUnit0();
    }

    private void bindNoiseTexture() {
        int customNoise = this.customTextureManager.getNoiseTextureId();
        int texture = customNoise != -1 ? customNoise : this.noiseTexture.getTextureId();
        bindTextureUnit(NOISE_TEX_UNIT, texture);
        bindTextureUnit(GBUFFER_NOISE_TEX_UNIT, texture);
        GlTextureUnits.resetToUnit0();
    }

    private void bindShadowSamplers() {
        if (this.shadowRenderer == null && this.stubShadowMap == null) {
            return;
        }
        int depth0;
        int depth1;
        int color0 = 0;
        int color1 = 0;
        if (this.shadowRenderer == null) {
            depth0 = this.stubShadowMap.getTextureId();
            depth1 = this.stubShadowMap.getTextureId();
        } else {
            depth0 = this.shadowRenderer.getDepthTextureId();
            depth1 = this.shadowRenderer.getDepthTextureNoTranslucentsId();
            color0 = this.shadowRenderer.getColorTextureId();
            color1 = this.shadowRenderer.getColorTexture1Id();
        }
        int sampler0 = shadowHardwareSamplerFor(0);
        int sampler1 = shadowHardwareSamplerFor(1);
        // OptiFine 1.12 packs declare sampler2DShadow shadowtex0/1 directly when shadowHardwareFiltering is enabled,
        // so the plain names keep the comparison sampler. Only a pack that opted into SEPARATE_HARDWARE_SAMPLERS
        // expects them to read raw depth, with comparison moved to the *HW aliases (Iris IrisSamplers:151).
        int plain0 = this.separateHardwareSamplers ? 0 : sampler0;
        int plain1 = this.separateHardwareSamplers ? 0 : sampler1;
        bindShadowDepthUnit(SHADOW_TEX_0_UNIT, depth0, plain0);
        bindShadowDepthUnit(SHADOW_TEX_1_UNIT, depth1, plain1);
        // The *HW aliases exist only for packs that declared SEPARATE_HARDWARE_SAMPLERS. For every other pack
        // nothing samples them, and holding two units hostage starves the pack's own custom textures and images —
        // which is exactly how Complementary's wsr_sampler/wsr_lod_sampler ended up with no unit at all and fell
        // back to unit 0, making its world-space reflections trace an empty voxel volume.
        if (this.separateHardwareSamplers) {
            bindShadowDepthUnit(SHADOW_TEX_0_HW_UNIT, depth0, sampler0);
            bindShadowDepthUnit(SHADOW_TEX_1_HW_UNIT, depth1, sampler1);
        }
        bindShadowDepthUnit(GBUFFER_SHADOW_TEX_0_UNIT, depth0, plain0);
        bindShadowDepthUnit(GBUFFER_SHADOW_TEX_1_UNIT, depth1, plain1);
        bindTextureUnit(SHADOW_COLOR_0_UNIT, color0);
        bindTextureUnit(SHADOW_COLOR_1_UNIT, color1);
        bindTextureUnit(GBUFFER_SHADOW_COLOR_0_UNIT, color0);
        bindTextureUnit(GBUFFER_SHADOW_COLOR_1_UNIT, color1);
        GlTextureUnits.resetToUnit0();
    }

    private void bindShadowDepthUnit(int unit, int texture, int sampler) {
        LWJGL.glBindSampler(unit, sampler);
        bindTextureUnit(unit, texture);
    }

    /**
     * Binds one sampler unit, leaving the selector <em>on that unit</em> — callers batch several of these and reset
     * once via {@link GlTextureUnits#resetToUnit0()} rather than paying a reset per bind. Every caller does; that is
     * load-bearing for units at or above {@link GlTextureUnits#CACHED_UNITS}, which take the raw branch.
     */
    private static void bindTextureUnit(int unit, int texture) {
        if (unit < GlTextureUnits.CACHED_UNITS) {
            // Low OptiFine 1.12 sampler units overlap Minecraft's cached texture slots. Keep that cache coherent
            // or later GlStateManager binds can be skipped while the actual GL unit still holds depth/shadow data.
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + unit);
            GlStateManager.bindTexture(texture);
        } else {
            GlTextureUnits.selectScratch(unit);
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        }
    }

    /**
     * The comparison sampler for a shadow depth texture. This is bound only to the {@code *HW} units, which only
     * programs that declared a {@code sampler2DShadow} are pointed at — and such a program is undefined without
     * depth comparison. So comparison is unconditional here; {@code shadowHardwareFiltering} only selects the
     * filtering flavour, exactly as it does for the raw units through the texture's own parameters.
     */
    private int shadowHardwareSamplerFor(int index) {
        if (this.shadowMipmap[index]) {
            return this.shadowNearest[index] ? this.shadowMippedNearestHwSampler : this.shadowMippedLinearHwSampler;
        }
        return this.shadowNearest[index] ? this.shadowNearestHwSampler : this.shadowLinearHwSampler;
    }

    /**
     * Binds the readable {@code colortex4..7} textures to their sampler units for the gbuffer/world phase. On the
     * 1.12 OptiFine path, {@code colortex4..7}/{@code gaux1..4} live on aux units {@code 7..10}; fullscreen programs
     * use their own table. Gbuffer programs sample these — most importantly MakeUp and other packs read
     * {@code gaux4} (= colortex7) in {@code gbuffers_terrain} as the atmosphere/fog color that distant terrain fades
     * toward. Without this bind, unit 7 held a stale/garbage texture, so the fog blended distant terrain toward a huge
     * value (clamped to the shader's 50.0 ceiling) — the blown-out horizon band, which also dragged auto-exposure down.
     * <p>
     * Units 0..3 are deliberately left alone: unit 0 is the block atlas, unit 1 the lightmap, and units 2/3 the PBR
     * normals/specular maps ({@link #bindGbufferPbrSamplers}). Custom-texture overrides (e.g. gaux2 -> a pack noise
     * texture) point their sampler uniforms at their own high units and are unaffected. Called once at gbuffer start
     * and re-asserted on every fixed-function phase switch, since vanilla/Sodium may disturb these units mid-frame.
     */
    private void bindGbufferColorSamplers() {
        bindGbufferColorSamplers(this.activeGbufferSamplerFlips);
    }

    private void bindGbufferColorSamplers(BitSet samplerFlips) {
        for (int i = 4; i < IrisRenderTargets.MAX_COLOR_BUFFERS; i++) {
            if (this.renderTargets.get(i) == null) {
                continue;
            }
            IrisRenderTarget target = this.renderTargets.get(i);
            int texture = samplerFlips.get(i) ? target.getAltTexture() : target.getMainTexture();
            int unit = GBUFFER_COLOR_TEXTURE_UNITS[i];
            if (unit < 0) {
                continue;
            }
            if (unit < GlTextureUnits.CACHED_UNITS) {
                GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + unit);
                GlStateManager.bindTexture(texture);
            } else {
                GlTextureUnits.selectScratch(unit);
                LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            }
        }
        // See bindColorSamplers: a bare setActiveTexture here can be swallowed after the raw branch.
        GlTextureUnits.resetToUnit0();
    }

    private BitSet prepareGbufferFeedbackSamplers(int[] drawBuffers) {
        BitSet samplerFlips = null;
        for (int logicalIndex : drawBuffers) {
            if (!isGbufferFeedbackSampler(logicalIndex) || this.renderTargets.get(logicalIndex) == null) {
                continue;
            }
            copyGbufferFrontToBack(logicalIndex);
            if (samplerFlips == null) {
                samplerFlips = (BitSet) this.activeGbufferSamplerFlips.clone();
            }
            samplerFlips.flip(logicalIndex);
        }
        return samplerFlips == null ? this.activeGbufferSamplerFlips : samplerFlips;
    }

    private static boolean isGbufferFeedbackSampler(int logicalIndex) {
        return logicalIndex >= 0
                && logicalIndex < GBUFFER_COLOR_TEXTURE_UNITS.length
                && GBUFFER_COLOR_TEXTURE_UNITS[logicalIndex] >= 0;
    }

    private void copyGbufferFrontToBack(int logicalIndex) {
        if (this.gbufferFeedbackCopyFramebuffer == null) {
            this.gbufferFeedbackCopyFramebuffer = new IrisFramebuffer();
        }
        int source = frontTexture(this.activeGbufferSamplerFlips, logicalIndex);
        int destination = backTexture(this.activeGbufferSamplerFlips, logicalIndex);
        this.gbufferFeedbackCopyFramebuffer.addColorAttachment(0, 0, source);
        this.gbufferFeedbackCopyFramebuffer.bindAsReadBuffer();
        LWJGL.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        int previousTexture = bindScratchTexture2D(destination);
        try {
            LWJGL.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0,
                    this.renderTargets.getWidth(), this.renderTargets.getHeight());
        } finally {
            restoreScratchTexture2D(previousTexture);
        }
    }

    private void restoreTextureUnits() {
        this.customTextureManager.unbindAll();
        this.customImageManager.unbindAll();
        for (int unit = DEPTH_TEX_0_UNIT; unit <= DEPTH_TEX_2_UNIT; unit++) {
            LWJGL.glBindSampler(unit, 0);
            bindTextureUnit(unit, 0);
        }
        for (int unit : new int[]{SHADOW_COLOR_0_UNIT, SHADOW_COLOR_1_UNIT, SHADOW_TEX_0_UNIT, SHADOW_TEX_1_UNIT,
                SHADOW_TEX_0_HW_UNIT, SHADOW_TEX_1_HW_UNIT, NOISE_TEX_UNIT, GBUFFER_DEPTH_TEX_0_UNIT,
                GBUFFER_DEPTH_TEX_1_UNIT, GBUFFER_SHADOW_COLOR_0_UNIT, GBUFFER_SHADOW_COLOR_1_UNIT,
                GBUFFER_SHADOW_TEX_0_UNIT, GBUFFER_SHADOW_TEX_1_UNIT, GBUFFER_NOISE_TEX_UNIT}) {
            LWJGL.glBindSampler(unit, 0);
            bindTextureUnit(unit, 0);
        }
        // colortex8..15 sit beyond GlStateManager's 8-slot cache — unbind those with raw GL (indexing the cache at
        // unit 8+ throws ArrayIndexOutOfBounds); units 0..7 go through GlStateManager to keep its cache coherent.
        for (int i = IrisRenderTargets.MAX_COLOR_BUFFERS - 1; i >= 8; i--) {
            LWJGL.glBindSampler(i, 0);
            GlTextureUnits.selectScratch(i);
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
        // Force GlStateManager's activeTextureUnit cache to agree with real GL before touching units 0..7.
        //
        // A bare LWJGL.glActiveTexture(GL_TEXTURE0) here is NOT enough: it moves real GL without telling
        // GlStateManager, and GlStateManager.setActiveTexture is itself cached, so the loop below can no-op its
        // first iteration and then drive real GL and the cache out of step for every remaining unit. Stepping
        // through a different unit first guarantees the second call actually issues, so both end at unit 0 no
        // matter what the cache held on entry.
        //
        // This is not hypothetical bookkeeping — it is what turned the screen white until a shader reload.
        // Vanilla's screenshot path (ScreenShotHelper.createScreenshot) calls the CACHED
        // GlStateManager.bindTexture(framebuffer.framebufferTexture) and then glGetTexImage. With the cache
        // desynced, that bind lands on the real (wrong) unit while the cache records framebufferTexture against
        // a unit that does not have it — so glGetTexImage reads the wrong texture (white PNG), and from then on
        // Framebuffer.bindFramebufferTexture()'s identical cached bind no-ops forever, leaving the fullscreen
        // blit sampling whatever is genuinely on that unit (shadowcolor0 clears to white). Hence: white screen,
        // every frame, until a reload rebinds and resyncs. See also MIPMAP_SCRATCH_UNIT and the standing rule
        // that raw texture binds must stay off units 0..7.
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + 1);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        for (int i = 7; i >= 0; i--) {
            LWJGL.glBindSampler(i, 0);
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + i);
            GlStateManager.bindTexture(0);
        }
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    private void bindShaderPackResources() {
        bindShaderPackResources(true);
    }

    private void bindShaderPackResources(boolean stableVisibleFloodfill) {
        if (this.destroyed) {
            return;
        }
        if (this.customImageManager != null && !this.customImageManager.isEmpty()) {
            LWJGL.glMemoryBarrier(SHADER_PACK_RESOURCE_BARRIERS);
        }
        if (this.customTextureManager != null) {
            this.customTextureManager.bindAll();
        }
        if (this.customImageManager != null) {
            this.customImageManager.bindAll(stableVisibleFloodfill);
        }
    }

    public void bindCustomImages() {
        bindShaderPackResources();
    }

    // ------------------------------------------------------------------ teardown

    /** Frees all GL resources. Must run on the render thread. Safe to call more than once. */
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        this.worldRenderingActive = false;
        this.centerDepthSampler.destroy();
        this.colorSpaceConverter.destroy();
        if (this.shaderStorageBuffers != null) {
            this.shaderStorageBuffers.destroy();
            this.shaderStorageBuffers = null;
        }
        com.bdmajora.impetus.iris.uniforms.custom.ActiveCustomUniforms.clear();
        com.bdmajora.impetus.iris.terrain.IrisTerrainProgramOverride.destroyShadowPrograms();
        if (this.shadowRenderer != null) {
            this.shadowRenderer.destroy();
        }
        if (this.gbufferPrograms != null) {
            this.gbufferPrograms.destroy();
        }
        activeGbufferSamplerUnits = GBUFFER_SAMPLER_UNITS;
        activeGbufferSamplerOverrides = java.util.Collections.emptyMap();
        com.bdmajora.impetus.iris.material.WorldRenderingSettings.setBlockStateIds(null);
        com.bdmajora.impetus.iris.material.WorldRenderingSettings.setBlockRenderLayers(null);
        com.bdmajora.impetus.iris.material.WorldRenderingSettings.setItemIds(null);
        com.bdmajora.impetus.iris.material.WorldRenderingSettings.setEntityIds(null);
        com.bdmajora.impetus.iris.material.WorldRenderingSettings.setVoxelRenderDistanceChunks(0);
        com.bdmajora.impetus.iris.material.WorldRenderingSettings.setDynamicHandLight(true);
        com.bdmajora.impetus.iris.material.WorldRenderingSettings.setSeparateAo(false);
        com.bdmajora.impetus.iris.material.WorldRenderingSettings.setOldLighting(false);
        com.bdmajora.impetus.iris.material.WorldRenderingSettings.setOldHandLight(true);
        com.bdmajora.impetus.iris.material.WorldRenderingSettings.setVoxelizeLightBlocks(false);
        // Every compute program is owned by the family pass it is attached to.
        destroyFamilyComputes(this.setupPasses);
        destroyFamilyComputes(this.beginPasses);
        destroyFamilyComputes(this.shadowCompPasses);
        destroyFamilyComputes(this.preparePasses);
        destroyFamilyComputes(this.deferredPasses);
        destroyFamilyComputes(this.passes);
        if (this.customTextureManager != null) {
            this.customTextureManager.destroy();
        }
        if (this.customImageManager != null) {
            this.customImageManager.destroy();
        }
        // Programs/uniforms are cached by name — destroy them once here, not per-pass.
        for (IrisProgram program : this.compiledPrograms.values()) {
            if (program != null) {
                program.destroy();
            }
        }
        this.compiledPrograms.clear();
        this.compiledUniforms.clear();
        this.setupPasses.clear();
        this.beginPasses.clear();
        this.shadowCompPasses.clear();
        this.preparePasses.clear();
        this.deferredPasses.clear();
        this.passes.clear();
        if (this.blitSourceFramebuffer != null) {
            this.blitSourceFramebuffer.destroy();
            this.blitSourceFramebuffer = null;
        }
        for (SwapPass swap : this.swapPasses) {
            swap.from.destroy();
        }
        this.swapPasses.clear();
        if (this.translucentGbufferFramebuffer != null && this.translucentGbufferFramebuffer != this.gbufferFramebuffer) {
            this.translucentGbufferFramebuffer.destroy();
        }
        this.translucentGbufferFramebuffer = null;
        if (this.gbufferFeedbackCopyFramebuffer != null) {
            this.gbufferFeedbackCopyFramebuffer.destroy();
            this.gbufferFeedbackCopyFramebuffer = null;
        }
        if (this.gbufferFramebuffer != null) {
            this.gbufferFramebuffer.destroy();
            this.gbufferFramebuffer = null;
        }
        if (this.quadRenderer != null) {
            this.quadRenderer.destroy();
        }
        if (this.noiseTexture != null) {
            this.noiseTexture.destroy();
        }
        if (this.defaultNormals != null) {
            this.defaultNormals.destroy();
        }
        if (this.defaultSpecular != null) {
            this.defaultSpecular.destroy();
        }
        if (this.noOverlayTexture != null) {
            this.noOverlayTexture.destroy();
        }
        if (this.stubShadowMap != null) {
            this.stubShadowMap.destroy();
        }
        LWJGL.glDeleteSamplers(this.shadowLinearHwSampler);
        LWJGL.glDeleteSamplers(this.shadowNearestHwSampler);
        LWJGL.glDeleteSamplers(this.shadowMippedLinearHwSampler);
        LWJGL.glDeleteSamplers(this.shadowMippedNearestHwSampler);
        this.renderTargets.destroy();
    }
}
