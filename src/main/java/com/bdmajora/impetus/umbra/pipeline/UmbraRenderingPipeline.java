package com.bdmajora.impetus.umbra.pipeline;

import com.bdmajora.impetus.umbra.gl.GlTextureUnits;
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
import com.bdmajora.impetus.umbra.gl.framebuffer.UmbraFramebuffer;
import com.bdmajora.impetus.umbra.gl.program.DrawBuffers;
import com.bdmajora.impetus.umbra.gl.blending.ProgramBlendState;
import com.bdmajora.impetus.umbra.gl.program.GlProgram;
import com.bdmajora.impetus.umbra.gl.program.UmbraProgram;
import com.bdmajora.impetus.umbra.gl.program.ProgramBuilder;
import com.bdmajora.impetus.umbra.gl.program.ProgramUniforms;
import com.bdmajora.impetus.umbra.gl.shader.GlShader;
import com.bdmajora.impetus.umbra.gl.shader.ShaderType;
import com.bdmajora.impetus.umbra.gl.texture.InternalTextureFormat;
import com.bdmajora.impetus.umbra.gl.texture.PlainTexture;
import com.bdmajora.impetus.umbra.gl.texture.StubShadowMap;
import com.bdmajora.impetus.umbra.shaderpack.ProgramSource;
import com.bdmajora.impetus.umbra.shaderpack.ShaderPack;
import com.bdmajora.impetus.umbra.shaderpack.loading.ProgramArrayId;
import com.bdmajora.impetus.umbra.shaderpack.loading.ProgramId;
import com.bdmajora.impetus.umbra.shaderpack.preprocessor.GlslPreprocessor;
import com.bdmajora.impetus.umbra.shaderpack.texture.CustomTextureTransformer;
import com.bdmajora.impetus.umbra.shaderpack.texture.TextureStage;
import com.bdmajora.impetus.umbra.targets.BufferFlipper;
import com.bdmajora.impetus.umbra.targets.DepthTexture;
import com.bdmajora.impetus.umbra.targets.UmbraRenderTarget;
import com.bdmajora.impetus.umbra.targets.UmbraRenderTargets;
import com.bdmajora.impetus.umbra.targets.NoiseTexture;
import com.bdmajora.impetus.umbra.terrain.FullscreenTransformer;
import com.bdmajora.impetus.umbra.terrain.ModernPackTransformer;
import com.bdmajora.impetus.umbra.terrain.VanillaNameTransformer;
import com.bdmajora.impetus.umbra.uniforms.CapturedRenderingState;
import com.bdmajora.impetus.umbra.uniforms.CameraUniforms;
import com.bdmajora.impetus.umbra.uniforms.CelestialUniforms;
import com.bdmajora.impetus.umbra.uniforms.CommonUniforms;
import com.bdmajora.impetus.umbra.uniforms.EyeBrightnessTracker;
import com.bdmajora.impetus.umbra.uniforms.FrameUpdateNotifier;
import com.bdmajora.impetus.umbra.uniforms.MatrixUniforms;
import com.bdmajora.impetus.umbra.uniforms.SystemTimeUniforms;
import com.bdmajora.impetus.umbra.features.FeatureFlags;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL12;
import com.bdmajora.impetus.lwjgl.GL13;
import com.bdmajora.impetus.lwjgl.GL14;
import com.bdmajora.impetus.lwjgl.GL15;
import com.bdmajora.impetus.lwjgl.GL30;
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

// The frame pipeline: the gbuffer FBO world rendering is redirected into, and the composite and final chain
// beginWorldRendering binds the gbuffer, captureRenderingState copies the camera matrices, finishWorldRendering
// runs each pass ping-ponging targets like OptiFine then final into vanilla's framebuffer
// Every pass compiles up front; a failure skips that pass. Render thread only
public class UmbraRenderingPipeline {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");

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
    // iris_overlay - Umbra's entity damage/hurt overlay sampler
    // 1.12.2 draws that flash as a separate fixed-function pass rather than through a sampler, so there
    // is no live overlay texture to expose; a fully transparent 1x1 is bound instead, which is exactly
    // the "no overlay" value Umbra's own fallback provides
    // unit 11 is the one gap in OptiFine's 1.12 gbuffers layout - 7..10 are gaux1..4, 12 is depthtex1
    private static final int GBUFFER_OVERLAY_UNIT = 11;
    // iris_overlay for the gbuffers stage lives on GBUFFER_OVERLAY_UNIT; this is the fullscreen-stage
    // bind of the same 1x1 dummy
    // it sits ABOVE the sampleable range on purpose - no composite/deferred/final program declares
    // iris_overlay, since it is an entity hurt-flash concept, so reserving a scarce low unit for it just
    // starved the pack's own samplers
    private static final int OVERLAY_TEX_UNIT = 34;
    // Highest logical colortex index shader-pack gbuffer stages may address. FBO attachment points are packed.
    private static final int GBUFFER_ATTACHMENT_LIMIT = UmbraRenderTargets.MAX_COLOR_BUFFERS;
    // High texture unit used transiently for depth-copy binds so no vanilla-tracked unit is disturbed.
    private static final int DEPTH_COPY_SCRATCH_UNIT = 33;
    // scratch unit for mipmap generation and reset, ABOVE every sampler allocation (colortex 0..15,
    // depth 16..18, shadow 19..25, noise 23, custom textures 26+, custom images 27..31)
    // mipmap ops bind textures raw, and doing that on unit 0 desyncs GlStateManager's 8-slot cache, after
    // which bindColorSamplers' "already bound" checks skip the real rebind and a pass samples whatever
    // mipmap target was bound last (BSL deferred1: colortex0 ended up reading the black colortex6, so the
    // whole screen went black)
    private static final int MIPMAP_SCRATCH_UNIT = 32;

    // lowest unit the pack's custom textures and images may use
    // the shadowtex*HW units above it are only real when the pack declared SEPARATE_HARDWARE_SAMPLERS;
    // otherwise nothing samples them and they are handed to the pack instead - Complementary needs seven
    // custom sampler units (gaux4, colortex3, voxel, floodfill x2, wsr, wsr_lod) and silently lost the
    // last two when the budget stopped at 26
    // the first custom unit is shared only by transient depth-copy and capture helpers; custom textures
    // are rebound after those scratch uses
    private static final int CUSTOM_TEX_FIRST_UNIT = SHADOW_TEX_0_HW_UNIT;
    private static final int GL_MAX_TEXTURE_IMAGE_UNITS = 0x8872;
    private static final int GL_BACK_BUFFER = 0x0405;
    private static final int SHADER_PACK_RESOURCE_BARRIERS = 0x00000020 | 0x00000008 | 0x00002000;
    private static final int FULL_BRIGHT_LIGHTMAP = 0x00F000F0;
    // Both halves of #FULL_BRIGHT_LIGHTMAP as the raw texcoord the lightmap texture matrix expects.
    private static final float FULL_BRIGHT_LIGHTMAP_COORD = 240.0f;
    private static final float LIGHTMAP_TEXTURE_SCALE = 1.0f / 256.0f;
    private static final float LIGHTMAP_TEXTURE_OFFSET = 8.0f / 256.0f;
    // Draw-buffer mask for fixed-function content with no pack program: plain color into colortex0 only.
    private static final int[] FIXED_FUNCTION_MASK = {0};
    private static final String[] LEGACY_COLOR_TARGETS =
            {"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"};
    private static final Pattern MIPMAP_DIRECTIVE =
            Pattern.compile("const\\s+bool\\s+(\\w+?)MipmapEnabled\\s*=\\s*(true|false)\\s*;");
    // Umbra's PackDirectives defaults. The half-lives are in deciseconds (1/10 s = 2 ticks).
    private static final float DEFAULT_CENTER_DEPTH_HALF_LIFE = 1.0f;
    private static final float DEFAULT_WETNESS_HALF_LIFE = 600.0f;
    private static final float DEFAULT_DRYNESS_HALF_LIFE = 200.0f;
    private static final float DEFAULT_EYE_BRIGHTNESS_HALF_LIFE = 10.0f;
    // Sampler name -> logical colortex index, independent from the texture unit chosen for a stage.
    private static final Map<String, Integer> COLOR_TARGETS_BY_NAME = new LinkedHashMap<>();
    // Sampler name -> texture unit for deferred/composite/final programs.
    private static final Map<String, Integer> FULLSCREEN_SAMPLER_UNITS = new LinkedHashMap<>();
    // Sampler name -> texture unit for gbuffers/shadow-stage programs.
    private static final Map<String, Integer> GBUFFER_SAMPLER_UNITS = new LinkedHashMap<>();
    private static final int[] GBUFFER_COLOR_TEXTURE_UNITS = new int[UmbraRenderTargets.MAX_COLOR_BUFFERS];

    static {
        for (int i = 0; i < UmbraRenderTargets.MAX_COLOR_BUFFERS; i++) {
            COLOR_TARGETS_BY_NAME.put("colortex" + i, i);
            FULLSCREEN_SAMPLER_UNITS.put("colortex" + i, i);
            if (i < LEGACY_COLOR_TARGETS.length) {
                COLOR_TARGETS_BY_NAME.put(LEGACY_COLOR_TARGETS[i], i);
                FULLSCREEN_SAMPLER_UNITS.put(LEGACY_COLOR_TARGETS[i], i);
            }
            GBUFFER_COLOR_TEXTURE_UNITS[i] = -1;
        }
        // OptiFine 1.12 gbuffers stage: gaux1..4 live on texture units 7..10. Umbra exposes the matching colortex
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

    // Registers a sampler name in the fullscreen layout
    private static void putSharedSampler(Map<String, Integer> fullscreen, String name, int unit) {
        fullscreen.put(name, unit);
        GBUFFER_SAMPLER_UNITS.put(name, unit);
    }

    // Registers a sampler name in the gbuffer layout
    private static void putGbufferSampler(String name, int unit) {
        GBUFFER_SAMPLER_UNITS.put(name, unit);
    }

    // one entry of a numbered pass family: a full-screen draw (program != null) into its own framebuffer,
    // or the final pass (drawn to the screen, framebuffer == null), or a compute-only entry
    // (program == null) for a family index the pack only supplies .csh files for
    // computes are the program's compute stages - <name>.csh and the letter-suffixed <name>_a.csh ..
    // <name>_z.csh
    // Umbra dispatches them *before* the pass's own draw, under the same flip state: Photon's
    // deferred4_a.csh writes the skylight SH that deferred4 then reads
    private static final class FullscreenPass {
        final String name;
        final UmbraProgram program;
        final ProgramUniforms uniforms;
        final UmbraFramebuffer framebuffer;
        // Per color buffer, the texture to bind as colortexN when this pass runs (0 = target not in use).
        final int[] colorSamplers;
        // Logical render targets in shader output-slot order, used for per-target blend directives.
        final int[] drawBuffers;
        final ProgramBlendState blendState;
        final BitSet flipsBefore;
        final BitSet flipsAfter;
        final BitSet mipmappedBuffers;
        // Compute stages dispatched before this pass draws; never null, usually empty.
        final List<ComputePass> computes;
        // viewport for this pass, taken from its draw buffers' size (size.buffer.colortexN)
        // zero means "the current render size", which is the case for every pass of a pack that declares
        // no buffer sizes
        int viewportWidth;
        int viewportHeight;
        // scale.<program> - Umbra's ViewportData
        // unlike viewportWidth, which reflects a target the pack resized, this shrinks the rasterised
        // rectangle inside whatever size the pass already has
        // defaults to the full viewport: scale 1, no offset
        float viewportScale = 1.0f;
        float viewportOffsetX;
        float viewportOffsetY;

        FullscreenPass(String name, UmbraProgram program, ProgramUniforms uniforms, UmbraFramebuffer framebuffer,
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

        // Umbra's CompositeRenderer.ComputeOnlyPass: a family slot with computes but no vertex/fragment
        // pair
        // it draws nothing, so it flips nothing, but it still needs the flip state and colortex snapshot
        // of its position in the chain - that is what its computes read and image-write
        static FullscreenPass computeOnly(String name, int[] colorSamplers, BitSet flips, List<ComputePass> computes) {
            return new FullscreenPass(name, null, null, null, colorSamplers, DrawBuffers.DEFAULT.clone(), null,
                    flips, (BitSet) flips.clone(), new BitSet(), computes);
        }
    }

    private final UmbraRenderTargets renderTargets;
    private final FullscreenQuadRenderer quadRenderer;
    private final NoiseTexture noiseTexture;
    // Fallback PBR inputs for the gbuffer stage: flat up-normal and black specular (OptiFine's defaults).
    private final PlainTexture defaultNormals;
    private final PlainTexture defaultSpecular;
    // The "no overlay active" stand-in bound on the iris_overlay units.
    private final PlainTexture noOverlayTexture;
    // "Always lit" 1×1 shadow map on the shadowtex units until the real shadow pass exists.
    private final StubShadowMap stubShadowMap;
    // whether the pack DECLARED SEPARATE_HARDWARE_SAMPLERS, not whether this port could provide it -
    // Umbra reads programSet.getPack().hasFeature(...) for exactly this (UmbraRenderingPipeline.java:222)
    // keying it off isUsable() made it permanently true, which suppressed the GL_TEXTURE_COMPARE_MODE
    // that UmbraShadowRenderer.createShadowDepthTexture otherwise sets on the shadow depth textures,
    // leaving hardware depth compare supplied only by per-unit sampler objects
    // any path that rebinds a shadow unit without also restoring its sampler object then leaves a
    // sampler2DShadow reading a texture whose compare mode is NONE - undefined, and "fully lit" on NVIDIA
    private boolean separateHardwareSamplers;
    private final boolean[] shadowHardwareFiltering = new boolean[2];
    private final boolean[] shadowMipmap = new boolean[2];
    private final boolean[] shadowNearest = new boolean[2];
    private final int shadowLinearHwSampler;
    private final int shadowNearestHwSampler;
    private final int shadowMippedLinearHwSampler;
    private final int shadowMippedNearestHwSampler;
    // ONE baked frame schedule, exactly like Umbra
    // the composite chain flips some colortex buffers an odd number of times per frame - Complementary's
    // colortex2 TAA history, written once by composite6 - and instead of alternating schedules per frame,
    // the frame ENDS by copying each such buffer's alt side back to main (see SwapPass, Umbra's
    // FinalPassRenderer.SwapPass)
    // every frame therefore starts from the canonical state "main = latest", and all baked FBOs and
    // sampler snapshots stay valid forever
    // cross-frame temporal accumulation (TAA) works because a history pass reads main - last frame's
    // copy-back - and writes alt
    private UmbraFramebuffer gbufferFramebuffer;
    private UmbraFramebuffer translucentGbufferFramebuffer;
    // the setup family: compute-only, dispatched *once* after the pipeline is built rather than every
    // frame - Umbra runs it when the dimension changes, and Photon uses it to seed its LPV volumes
    private final List<FullscreenPass> setupPasses = new ArrayList<>();
    private boolean setupDispatched;
    // prepareBeforeShadow: run the prepare family before the shadow map instead of after it
    // packs whose shadow pass samples what prepare produces need this ordering
    private boolean prepareBeforeShadow;
    // allowConcurrentCompute: skip the full memory barrier between consecutive compute dispatches
    // only safe when the pack states its dispatches are independent
    private boolean allowConcurrentCompute;
    // rain.depth — whether rain/snow writes into the depth buffer (Umbra shouldWriteRainAndSnowToDepthBuffer).
    private boolean rainDepth;
    // beacon.beam.depth — whether the beacon beam writes into the depth buffer.
    private boolean beaconBeamDepth;
    // frustum.culling / occlusion.culling — vanilla culling switches; both default on.
    private boolean frustumCulling = true;
    private boolean occlusionCulling = true;
    // skipAllRendering — draw no world geometry at all, leaving only the composite chain.
    private boolean skipAllRendering;
    // separateEntityDraws — entities render in their own pass after the deferred chain.
    private boolean separateEntityDraws;
    // particles.ordering = mixed | after | before, relative to the deferred chain.
    private String particleOrdering = "mixed";
    // backFace.solid|cutout|cutoutMipped|translucent - per-terrain-layer back-face culling
    // vanilla culls every layer; a pack that shades both sides of a face, or reads geometry from the
    // light's side, asks for a layer's back faces to be kept
    // indexed by BlockRenderLayer#ordinal()
    private final boolean[] backFaceCulling = {true, true, true, true};
    // The begin family: runs at the very start of world rendering, before anything is drawn.
    private final List<FullscreenPass> beginPasses = new ArrayList<>();
    // the prepare family: runs after the shadow map, before the gbuffers
    // Photon's prepare builds the cloud shadow map and cumulus coverage map into colortex8, which its
    // terrain lighting and sky both read
    private final List<FullscreenPass> preparePasses = new ArrayList<>();
    private final List<FullscreenPass> deferredPasses = new ArrayList<>();
    private final List<FullscreenPass> passes = new ArrayList<>();
    private UmbraFramebuffer blitSourceFramebuffer;
    private final List<SwapPass> swapPasses = new ArrayList<>();
    // Per-render-target clear directives parsed from the pack sources. Umbra defaults every colortex to clear=true.
    private final boolean[] colorBufferClears = new boolean[UmbraRenderTargets.MAX_COLOR_BUFFERS];
    // Explicit colortexNClearColor values; null means Umbra's default color for that buffer.
    private final float[][] colorBufferClearColors = new float[UmbraRenderTargets.MAX_COLOR_BUFFERS][];
    // Regular per-frame clears: only buffers whose colortexNClear directive is true, both main and alt sides.
    private final List<ClearPass> clearPasses = new ArrayList<>();
    // First-frame / resized-storage clears: every materialized buffer, both main and alt sides.
    private final List<ClearPass> fullClearPasses = new ArrayList<>();
    private boolean fullClearRequired = true;
    // fullscreen programs plus their uniforms, compiled once and cached by name
    // a name mapping to null means that program failed to compile
    // owns the GL programs - they are destroyed here, not per-pass
    private final java.util.Map<String, UmbraProgram> compiledPrograms = new java.util.HashMap<>();
    private final java.util.Map<String, ProgramUniforms> compiledUniforms = new java.util.HashMap<>();
    // The pack's fixed-function gbuffer programs (sky/entities/particles/weather/clouds/hand), phase-switched.
    private final GbufferPrograms gbufferPrograms;
    // The pack's custom textures (texture.*/customTexture.* directives) and their unit overrides.
    private final CustomTextureManager customTextureManager;
    // The pack's writable custom images (image.* directives — Complementary's colored-lighting volumes).
    private final CustomImageManager customImageManager;
    // the shadowcomp family, dispatched as a block right after the shadow map renders
    // compute stages only - a shadowcomp *raster* stage would draw into shadowcolor0/1, which
    // UmbraShadowRenderer does own, but nothing schedules those yet; Umbra does it in
    // ShadowCompositeRenderer
    private final List<FullscreenPass> shadowCompPasses = new ArrayList<>();
    // render targets a program writes through the image API (colorimgN), mapped to the image unit they
    // are bound on - Umbra's UmbraImages.addRenderTargetImages
    // Photon's deferred4_a.csh stores its skylight spherical harmonics with imageStore(colorimg4, ...);
    // with no such binding the compute writes nowhere and every surface loses its sky ambient
    // like Umbra, the bound texture follows the pass's buffer flips, so it is always the same side of the
    // ping-pong pair that colortexN reads
    private final Map<Integer, Integer> renderTargetImageUnits = new LinkedHashMap<>();
    // shadowcolorimg0/shadowcolorimg1 - the shadow colour attachments exposed through the image API
    // (Umbra UmbraImages.addShadowColorImages)
    // index 0/1 -> image unit, empty when the pack references neither or there is no shadow pass to own
    // the textures
    private final Map<Integer, Integer> shadowColorImageUnits = new LinkedHashMap<>();
    // Active shader macro environment: built-in MC/UMBRA macros plus the pack's resolved option values.
    private final Map<String, String> shaderDefines;

    // One compute dispatch: the linked program, its uniforms, and the work-group counts.
    private static final class ComputePass {
        final String name;
        final GlProgram program;
        final ProgramUniforms uniforms;
        final int groupsX;
        final int groupsY;
        final int groupsZ;
        // Screen-relative dispatch (const vec2 workGroupsRender); NaN = fixed dispatch.
        final float renderScaleX;
        final float renderScaleY;
        final int localSizeX;
        final int localSizeY;
        // Indirect dispatch (indirect.<pass> directive): GL buffer id, or -1 for direct dispatch.
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
    // the gbuffers/shadow-stage sampler overrides of the *active* pipeline, consulted by the static
    // assignSamplerUnitsToBoundProgram that the Impetus terrain and shadow overrides call - their program
    // objects are Impetus's, built lazily outside this class
    // set on construction, cleared on destroy
    private static volatile Map<String, CustomTextureManager.Override> activeGbufferSamplerOverrides =
            java.util.Collections.emptyMap();
    private static volatile Map<String, Integer> activeGbufferSamplerUnits = GBUFFER_SAMPLER_UNITS;
    // colour targets written (flipped) by at least one earlier pass while the composite/deferred chain is
    // being built
    // Umbra parity: a custom-texture override on a colortex deactivates once a pass has written that
    // buffer, because later passes must read the chain's content and not the custom texture
    // only mutated during construction
    private final TreeSet<Integer> flippedAtLeastOnce = new TreeSet<>();
    // Every color index attached to the gbuffer FBOs: the union of all gbuffer-stage DRAWBUFFERS masks, sorted.
    private final int[] gbufferAttachments;
    // Logical colortex index -> physical gbuffer attachment point.
    private final Map<Integer, Integer> gbufferAttachmentPoints = new LinkedHashMap<>();
    // The gbuffer FBO the world is currently rendering into (switches after the deferred chain runs).
    private UmbraFramebuffer currentGbuffer;
    // Scratch read FBO used to snapshot a gbuffer color target before a program reads and writes it.
    private UmbraFramebuffer gbufferFeedbackCopyFramebuffer;
    // Umbra-style colortex flip snapshot used by opaque gbuffers programs, before the deferred chain runs.
    private BitSet preTranslucentGbufferSamplerFlips = new BitSet();
    // Umbra-style colortex flip snapshot used by translucent gbuffers programs, after the deferred chain runs.
    private BitSet translucentGbufferSamplerFlips = new BitSet();
    // The flip snapshot currently used to bind colortex4..7 for gbuffers programs.
    private BitSet activeGbufferSamplerFlips = new BitSet();
    // The shadow-map pass, or null when the pack declares no shadow program.
    private final UmbraShadowRenderer shadowRenderer;
    private final FrameUpdateNotifier frameUpdateNotifier = new FrameUpdateNotifier();

    // centerDepthSmooth producer + its pack-configurable smoothing half-life (seconds).
    private final CenterDepthSampler centerDepthSampler = new CenterDepthSampler();
    private float centerDepthHalfLife = DEFAULT_CENTER_DEPTH_HALF_LIFE;

    // the pack-wide scalar const directives, with Umbra's defaults (from PackDirectives' constructor)
    // the three half-lives are in *deciseconds*, the unit Umbra's SmoothedFloat takes
    private int noiseTextureResolution = NoiseTexture.DEFAULT_RESOLUTION;
    private float ambientOcclusionLevel = 1.0f;
    private float wetnessHalfLife = DEFAULT_WETNESS_HALF_LIFE;
    private float drynessHalfLife = DEFAULT_DRYNESS_HALF_LIFE;
    private float eyeBrightnessHalfLife = DEFAULT_EYE_BRIGHTNESS_HALF_LIFE;

    // Optional final-presentation wide-gamut conversion (user-configured, defaults to sRGB = off).
    private final ColorSpaceConverter colorSpaceConverter = new ColorSpaceConverter();

    // Pack-declared shader storage buffers; null until construction.
    private com.bdmajora.impetus.umbra.gl.buffer.ShaderStorageBufferHolder shaderStorageBuffers;

    // indirect.<pass> directives: pass name → {bufferObject index, byte offset}.
    private Map<String, long[]> indirectDispatchPointers = java.util.Collections.emptyMap();

    // GL43 dispatch-indirect binding target (kept as a literal to avoid a hard generated-constant dependency).
    private static final int GL_DISPATCH_INDIRECT_BUFFER = 0x90EE;

    // end-of-frame alt->main copy-back for a buffer the chain left odd-flipped (Umbra
    // FinalPassRenderer.SwapPass)
    // "from" is a read framebuffer over the buffer's ALT texture; the copy target is its MAIN texture
    private static final class SwapPass {
        final UmbraFramebuffer from;
        final int targetTexture;
        final int index;
        // The target's own dimensions — a size.buffer-sized buffer must not be copied at the screen size.
        final int width;
        final int height;

        SwapPass(int index, UmbraFramebuffer from, int targetTexture, int width, int height) {
            this.index = index;
            this.from = from;
            this.targetTexture = targetTexture;
            this.width = width;
            this.height = height;
        }
    }

    private static final class ClearPass {
        final UmbraFramebuffer framebuffer;
        final float[] color;
        // Clear viewport; 0 means the current render size. Explicitly-sized buffers need their own.
        final int width;
        final int height;

        ClearPass(UmbraFramebuffer framebuffer, float[] color, int width, int height) {
            this.framebuffer = framebuffer;
            this.color = color;
            this.width = width;
            this.height = height;
        }
    }

    private boolean worldRenderingActive;
    private boolean destroyed;
    // true when the pack's fullscreen shaders are modern (#version 130+)
    // such passes position the quad with the fixed-function ftransform()/gl_TextureMatrix[0], so the
    // composite chain must run with identity model-view, projection and texture matrices - see runPass
    private boolean modernPack;
    public UmbraRenderingPipeline(ShaderPack pack) {
        Minecraft mc = Minecraft.getMinecraft();
        this.renderTargets = new UmbraRenderTargets(mc.displayWidth, mc.displayHeight);
        this.shaderDefines = pack.getEnvironmentDefines();
        // The GPU identity macros need a live GL context, so they are added here rather than baked into the pack's
        // environment defines. Without them every hardware-workaround gate in every pack silently took its
        // "unknown vendor" branch — Clarity's `#if ... defined MC_GL_VENDOR_NVIDIA` left `immut` expanding to nothing
        // instead of `const`.
        com.bdmajora.impetus.umbra.gl.shader.ShaderMacros.withGpuIdentity(this.shaderDefines,
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
            // Umbra/OptiFine text-scan every program in the pack; Sildur declares its HDR formats
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
                if (index < 0 || index >= UmbraRenderTargets.MAX_COLOR_BUFFERS) {
                    return;
                }
                boolean[] relative = pack.getProperties().getBufferSizeRelative(index);
                this.renderTargets.setColorSize(index, size[0], size[1], relative);
            });
            materializeSampledTargets(fullscreenSources);

            // Publish the pack's block.properties mapping for the chunk meshers (null keeps raw 1.12.2 IDs). Done
            // here rather than at pack parse because registry resolution needs the game fully initialized.
            com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setBlockStateIds(
                    com.bdmajora.impetus.umbra.material.BlockMaterialMapping.createBlockStateIdTable(pack.getIdMap()));
            com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setBlockRenderLayers(
                    com.bdmajora.impetus.umbra.material.BlockMaterialMapping.createBlockRenderLayerTable(pack.getIdMap()));
            com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setItemIds(pack.getIdMap().getItemIdMap());
            com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setEntityIds(pack.getIdMap().getEntityIdMap());
            com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setVoxelRenderDistanceChunks(
                    mc.gameSettings.renderDistanceChunks);

            // Custom images/textures must exist before any program compiles: sampler-unit assignment consults
            // the overrides (image uniforms are plain glUniform1i assignments like samplers).
            int firstCustomUnit = this.separateHardwareSamplers
                    ? SHADOW_TEX_1_HW_UNIT + 1 : CUSTOM_TEX_FIRST_UNIT;
            this.customTextureManager = new CustomTextureManager(pack, samplerUnitsByStage(), COLOR_TARGETS_BY_NAME,
                    firstCustomUnit, maxProgrammableTextureUnit());
            this.customImageManager = new CustomImageManager(pack.getProperties().getUmbraCustomImages(),
                    this.customTextureManager.getNextAvailableUnit(), maxProgrammableTextureUnit(),
                    mc.displayWidth, mc.displayHeight);
            allocateRenderTargetImageUnits(collectAllProgramSources(pack));
            activeGbufferSamplerUnits = GBUFFER_SAMPLER_UNITS;
            activeGbufferSamplerOverrides = mergedStageOverrides(TextureStage.GBUFFERS_AND_SHADOW);

            // Custom uniforms must exist before any program compiles, so every compile path can register them.
            com.bdmajora.impetus.umbra.uniforms.custom.ActiveCustomUniforms.set(
                    pack.getProperties().getCustomUniforms().build());

            // Pack-declared SSBOs (bufferObject.<index> directives), zero-filled and bound at fixed indices.
            this.shaderStorageBuffers = new com.bdmajora.impetus.umbra.gl.buffer.ShaderStorageBufferHolder(
                    com.bdmajora.impetus.umbra.gl.buffer.ShaderStorageBufferHolder.parseDefinitions(
                            pack.getProperties().getRaw()),
                    mc.displayWidth, mc.displayHeight);
            this.indirectDispatchPointers = parseIndirectPointers(pack.getProperties().getRaw());

            this.prepareBeforeShadow = pack.getProperties().getPrepareBeforeShadow().orElse(Boolean.FALSE);
            com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setDynamicHandLight(
                    pack.getProperties().getDynamicHandLight().orElse(Boolean.TRUE));
            com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setSeparateAo(
                    pack.getProperties().getSeparateAo().orElse(Boolean.FALSE));
            com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setOldLighting(
                    pack.getProperties().getOldLighting().orElse(Boolean.TRUE));
            com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setOldHandLight(
                    pack.getProperties().getOldHandLight().orElse(Boolean.TRUE));
            com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setVoxelizeLightBlocks(
                    pack.getProperties().getVoxelizeLightBlocks().orElse(Boolean.FALSE));
            this.allowConcurrentCompute = pack.getProperties().getAllowConcurrentCompute().orElse(Boolean.FALSE);
            this.rainDepth = pack.getProperties().getRainDepth().orElse(Boolean.FALSE);
            this.beaconBeamDepth = pack.getProperties().getBeaconBeamDepth().orElse(Boolean.FALSE);
            this.frustumCulling = pack.getProperties().getFrustumCulling().orElse(Boolean.TRUE);
            this.occlusionCulling = pack.getProperties().getOcclusionCulling().orElse(Boolean.TRUE);
            this.skipAllRendering = pack.getProperties().getSkipAllRendering().orElse(Boolean.FALSE);
            this.separateEntityDraws = pack.getProperties().getSeparateEntityDraws().orElse(Boolean.FALSE);
            // Umbra's resolution order: an explicit directive wins, otherwise a pack with a deferred chain that is
            // not using separate entity draws gets `after` (so particles are not lit by the deferred pass), and
            // everything else `mixed`.
            this.particleOrdering = pack.getProperties().getParticleOrdering().orElseGet(() -> {
                boolean hasDeferred = pack.getProgramSet().get(ProgramArrayId.Deferred, 0).isPresent();
                return hasDeferred && !this.separateEntityDraws ? "after" : "mixed";
            });
            // BlockRenderLayer order on 1.12.2 is SOLID, CUTOUT_MIPPED, CUTOUT, TRANSLUCENT.
            String[] backFaceKeys = {"solid", "cutoutMipped", "cutout", "translucent"};
            for (int i = 0; i < backFaceKeys.length; i++) {
                this.backFaceCulling[i] = pack.getProperties()
                        .getBackFaceCulling(backFaceKeys[i]).orElse(Boolean.TRUE);
            }
            this.gbufferPrograms = new GbufferPrograms(pack, GBUFFER_SAMPLER_UNITS, gbufferSamplerOverrideUnits());
            this.gbufferAttachments = computeGbufferAttachments(pack, terrainDrawBuffers(pack));
            for (int i = 0; i < this.gbufferAttachments.length; i++) {
                this.gbufferAttachmentPoints.put(this.gbufferAttachments[i], i);
                // A gbuffer FBO mixing attachment sizes renders into the intersection of them, which would silently
                // shrink the world pass. Packs size reflection/bloom buffers this way, never gbuffer outputs, so this
                // is a pack bug (or a bad parse) worth surfacing rather than a case to support.
                if (this.renderTargets.hasCustomSize(this.gbufferAttachments[i])) {
                    LOGGER.warn("[Umbra] colortex{} declares its own size but is also a gbuffer output; the world pass "
                                    + "would be clipped to {}x{}", this.gbufferAttachments[i],
                            this.renderTargets.getWidth(this.gbufferAttachments[i]),
                            this.renderTargets.getHeight(this.gbufferAttachments[i]));
                }
            }
            this.shadowRenderer = createShadowRenderer(pack);
            allocateShadowColorImageUnits(collectAllProgramSources(pack));
            BufferFlipper flipper = this.renderTargets.getBufferFlipper();

            // Bake the single schedule from the reset flip state, then record which buffers the chain leaves
            // odd-flipped: those get an end-of-frame alt->main copy-back (Umbra's SwapPass), so the next frame's
            // baked FBOs and sampler snapshots are valid again without any per-frame parity.
            buildSchedule(pack, flipper);
            buildSwapPasses(flipper);
            buildClearPasses();


            // Pipeline setup creates and checks Umbra FBOs as a side effect. Give Minecraft's main target back before
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

    // Highest unit a program can address, from the driver
    private static int maxProgrammableTextureUnit() {
        return Math.max(CUSTOM_TEX_FIRST_UNIT - 1, LWJGL.glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS) - 1);
    }

    // A sampler object with depth-compare on, for shadow2D lookups
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

    // The numbered families that render full-screen quads into the color targets, in the order they run.
    private static final ProgramArrayId[] FULLSCREEN_FAMILIES = {
            ProgramArrayId.Begin, ProgramArrayId.Prepare, ProgramArrayId.Deferred, ProgramArrayId.Composite
    };
    // NB: Setup is deliberately absent — it is compute-only, so it contributes no sampled targets or draw buffers.

    private static void collectFamily(ShaderPack pack, ProgramArrayId id, List<ProgramSource> sources) {
        for (int i = 0; i < id.getNumPrograms(); i++) {
            pack.getProgramSet().get(id, i).ifPresent(sources::add);
        }
    }

    // Every composite, deferred and final program the pack ships, in pass order
    private List<ProgramSource> collectFullscreenSources(ShaderPack pack) {
        List<ProgramSource> sources = new ArrayList<>();
        for (ProgramArrayId id : FULLSCREEN_FAMILIES) {
            collectFamily(pack, id, sources);
        }
        pack.getProgramSet().get(ProgramId.Final).ifPresent(sources::add);
        return sources;
    }

    // every present program source in the pack - all gbuffer families plus shadow, the numbered
    // deferred/composite arrays and final - for directive scanning only
    // buffer-format and clear directives (const int colortexNFormat, const bool colortexNClear, ...) are
    // conventionally placed inside a block comment in whichever file the pack author chose, and
    // Umbra/OptiFine text-scan the whole pack, so we must too
    // duplicates are harmless: the directive scan is idempotent, since re-setting a target to the same
    // format is a no-op
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

    // reserves an image unit for every colorimgN the pack references, above the units the image.<name>
    // directives took
    // Umbra assigns these per program from its ProgramImages builder; with this pipeline's fixed-unit
    // architecture one global unit per referenced target is equivalent and much simpler, because the
    // binding is re-established per pass anyway (see bindRenderTargetImages)
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
                LOGGER.error("[Umbra] Out of image units for colorimg{} (max {}); ignoring it", index, limit);
                continue;
            }
            // Umbra createIfUnsure()s the target: a buffer nothing samples but a compute writes still has to exist.
            this.renderTargets.getOrCreate(index);
            this.renderTargetImageUnits.put(index, unit);
            unit++;
        }
    }

    // reserves image units for shadowcolorimg0/1
    // runs after the render-target images so the two share one ascending allocation, and only when a
    // shadow renderer exists to own the textures
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
                LOGGER.error("[Umbra] Out of image units for shadowcolorimg{} (max {}); ignoring it", index, limit);
                continue;
            }
            this.shadowColorImageUnits.put(index, unit);
            unit++;
        }
    }

    // Finds colorimgN references so those targets get image bindings
    private static void collectRenderTargetImages(String source, TreeSet<Integer> out) {
        if (source == null) {
            return;
        }
        Matcher matcher = RENDER_TARGET_IMAGE.matcher(source);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index < UmbraRenderTargets.MAX_COLOR_BUFFERS) {
                out.add(index);
            }
        }
    }

    // OptiFine's pre-colortexNFormat way of upgrading gaux4/colortex7, written as a block comment in
    // the pack source: GAUX4FORMAT:RGB32F
    // only the three formats OptiFine accepted are honoured, matching Umbra's PackRenderTargetDirectives;
    // anything else is a pack error and keeps the default
    private void applyLegacyGaux4Format(List<ProgramSource> sources) {
        Pattern directive = Pattern.compile("/\\*\\s*GAUX4FORMAT\\s*:\\s*(\\w+)\\s*\\*/");
        for (ProgramSource source : sources) {
            for (String stage : activeDirectiveStages(source)) {
                Matcher matcher = directive.matcher(stage);
                while (matcher.find()) {
                    String name = matcher.group(1);
                    if (!name.equals("RGBA32F") && !name.equals("RGB32F") && !name.equals("RGB16")) {
                        LOGGER.warn("[Umbra] Ignoring GAUX4FORMAT:{} in '{}' — only RGBA32F, RGB32F and RGB16 are valid; "
                                + "use `const int colortex7Format = {};` instead", name, source.getName(), name);
                        continue;
                    }
                    InternalTextureFormat.fromString(name).ifPresent(format -> {
                        this.renderTargets.setColorFormat(7, format);
                    });
                }
            }
        }
    }

    // the stages of source a pack directive may be declared in, each with the pack's own preprocessor
    // conditionals resolved against the macro set that stage actually compiles with
    // directives must never be read from raw source: these scans take the LAST textual match, so a
    // directive the pack declared under a disabled #if silently wins over the live one
    // Umbra is immune because it scans source JCPP has already preprocessed (ShaderPack.java:317 feeds
    // ProgramSet -> ConstDirectiveParser)
    // OptiFine 1.12.2 does not preprocess, but its matchers filter on the VALUE -
    // isConstBoolSuffix("Clear", false) / ("MipmapEnabled", true), Shaders.java:2470/2499 - so it only
    // ever reads the polarity that is not the default and is accidentally immune to the common #if/#else
    // pair
    // Impetus declares IS_IRIS, so it owes the pack Umbra's semantics
    // Body Camera Shader v1.6.1 is the case that proved it: colortex0Format (R11F_G11F_B10F vs RGB8),
    // colortex5Clear (false vs true) and colortex0MipmapEnabled (true vs false) are each declared once
    // per branch of an #if, and all three resolved to the dead branch
    // that killed the pack's auto-exposure - a wiped accumulator makes its color /= tempExposure + 0.125
    // a flat 8x - and clipped its HDR buffer, for a uniformly white screen
    // option values reach the resolver even though getEnvironmentDefines() excludes them, because Impetus
    // applies them in place as real #define lines ahead of the #if; do NOT add options to the macro map
    // instead, that reintroduces the macro-redefinition failure across every non-Complementary pack
    // vertex first, fragment last: the scans are last-match-wins, and Umbra only ever trusts the fragment
    // stage (ProgramSet.java:263), so when the two disagree the fragment value has to be the one that
    // survives
    // scanning the vertex stage at all is a deliberate superset of Umbra, matching OptiFine, which scans
    // every file in the pack - Sildur declares real formats in a .vsh
    private List<String> activeDirectiveStages(ProgramSource source) {
        Map<String, String> macros =
                com.bdmajora.impetus.umbra.gl.shader.ShaderMacros.forProgram(this.shaderDefines, source.getName());
        List<String> stages = new ArrayList<>(2);
        for (Optional<String> stage : java.util.Arrays.asList(source.getVertexSource(), source.getFragmentSource())) {
            if (stage.isPresent()) {
                stages.add(GlslPreprocessor.resolveConditionals(stage.get(), macros));
            }
        }
        return stages;
    }

    // applies the pack's render-target format directives (const int colortex0Format = RGBA16;, including
    // the legacy gcolorFormat-style names) the OptiFine way: declared as consts anywhere in the
    // composite/deferred/final sources
    // must run before any target is materialized - HDR packs depend on it, because with plain RGBA8 their
    // tonemapping input is clamped and highlights blow out
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
                    if (index == null || index >= UmbraRenderTargets.MAX_COLOR_BUFFERS) {
                        continue; // not a color-target name (e.g. shadowcolor0Format — shadow pass comes later)
                    }
                    Optional<InternalTextureFormat> format = InternalTextureFormat.fromString(matcher.group(2));
                    if (!format.isPresent()) {
                        LOGGER.warn("[Umbra] '{}' requests unknown format {} for colortex{}; keeping RGBA8",
                                source.getName(), matcher.group(2), index);
                        continue;
                    }
                    try {
                        this.renderTargets.setColorFormat(index, format.get());
                    } catch (IllegalStateException e) {
                        LOGGER.warn("[Umbra] Format directive for colortex{} came after the target was created", index);
                    }
                }
                Matcher clearMatcher = clearDirective.matcher(stage);
                while (clearMatcher.find()) {
                    Integer index = COLOR_TARGETS_BY_NAME.get(clearMatcher.group(1));
                    if (index != null && index < UmbraRenderTargets.MAX_COLOR_BUFFERS) {
                        this.colorBufferClears[index] = Boolean.parseBoolean(clearMatcher.group(2));
                    }
                }
                Matcher clearColorMatcher = clearColorDirective.matcher(stage);
                while (clearColorMatcher.find()) {
                    Integer index = COLOR_TARGETS_BY_NAME.get(clearColorMatcher.group(1));
                    if (index != null && index < UmbraRenderTargets.MAX_COLOR_BUFFERS) {
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

    // the pack-wide scalar const directives Umbra collects into PackDirectives
    // (PackDirectives.acceptDirectivesFrom)
    // sunPathRotation and the shadow directives are read on the shadow path instead, which owns their
    // consumers
    // defaults and units are Umbra's: the half-lives are in *deciseconds* (SmoothedFloat scales by 0.1f),
    // and ambientOcclusionLevel is clamped to 0..1
    private void applyPackScalarDirectives(String activeText) {
        this.centerDepthHalfLife = parseConstFloat(activeText, "centerDepthHalflife", DEFAULT_CENTER_DEPTH_HALF_LIFE);

        // noisetex size. A pack that samples `texture2D(noisetex, uv * 32)` against a 256x256 noise texture gets the
        // wrong spatial frequency everywhere it uses noise — Body Camera's water normals are built from it.
        int noiseResolution = parseConstInt(activeText, "noiseTextureResolution", NoiseTexture.DEFAULT_RESOLUTION);
        if (noiseResolution > 0) {
            // NoiseTexture allocates resolution^2 * 4 bytes twice (a byte[] and a direct buffer), so a pack typo like
            // 65536 would OOM the client outright rather than render badly. 4096 is far past anything real.
            if (noiseResolution > 4096) {
                LOGGER.warn("[Umbra] Pack requests noiseTextureResolution={}; clamping to 4096", noiseResolution);
                noiseResolution = 4096;
            }
            this.noiseTextureResolution = noiseResolution;
        }

        // Vanilla's baked AO strength. Umbra pushes this into WorldRenderingSettings, where the block-model AO
        // computation reads it; 1.0 is vanilla, 0.0 disables vanilla AO so the pack can do its own.
        float aoLevel = parseConstFloat(activeText, "ambientOcclusionLevel", 1.0f);
        this.ambientOcclusionLevel = Math.max(0.0f, Math.min(1.0f, aoLevel));
        com.bdmajora.impetus.umbra.material.WorldRenderingSettings
                .setAmbientOcclusionLevel(this.ambientOcclusionLevel);

        // `wetness` and `eyeBrightnessSmooth` smoothing rates.
        this.wetnessHalfLife = parseConstFloat(activeText, "wetnessHalflife", DEFAULT_WETNESS_HALF_LIFE);
        this.drynessHalfLife = parseConstFloat(activeText, "drynessHalflife", DEFAULT_DRYNESS_HALF_LIFE);
        this.eyeBrightnessHalfLife =
                parseConstFloat(activeText, "eyeBrightnessHalflife", DEFAULT_EYE_BRIGHTNESS_HALF_LIFE);
        EyeBrightnessTracker.setHalfLives(this.wetnessHalfLife, this.drynessHalfLife, this.eyeBrightnessHalfLife);

    }

    // Four comma-separated floats
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

    // Tolerates a trailing f
    private static float parseFloatLiteral(String value) {
        String cleaned = value.trim();
        if (cleaned.endsWith("f") || cleaned.endsWith("F")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return Float.parseFloat(cleaned);
    }

    // creates every colour target the composite/final programs declare a sampler for, so per-pass sampler
    // snapshots can bind them even when the writing pass comes later in the chain
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

    // builds the shadow renderer when the pack declares a shadow program, with the OptiFine shadow
    // projection directives parsed anywhere in the pack sources
    private UmbraShadowRenderer createShadowRenderer(ShaderPack pack) {
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
            return null;
        }
        // OptiFine's pre-const spelling of the same three settings: `#define SHADOWRES 2048` etc. Umbra accepts both
        // (PackShadowDirectives), and the shaders.properties keys override either.
        int resolution = parseConstInt(activeText, "shadowMapResolution", parseDefineInt(text, "SHADOWRES", 1024));
        if (resolution <= 0) {
            // A pack error, but the literal grammar now admits a sign, and a non-positive texture size would fail
            // allocation rather than degrade. E-LITE declares `shadowMapResolution = 10` in its shadows-off branch,
            // which conditional resolution already hides; this only backstops the value actually reaching GL.
            LOGGER.warn("[Umbra] Pack declares shadowMapResolution={}; falling back to 1024", resolution);
            resolution = 1024;
        }
        float distance = parseConstFloat(activeText, "shadowDistance", parseDefineFloat(text, "SHADOWHPL", 160.0f));
        float nearPlane = parseConstFloat(activeText, "shadowNearPlane", UmbraShadowRenderer.DEFAULT_NEAR_PLANE);
        float farPlane = parseConstFloat(activeText, "shadowFarPlane", UmbraShadowRenderer.DEFAULT_FAR_PLANE);
        float intervalSize = parseConstFloat(activeText, "shadowIntervalSize", UmbraShadowRenderer.DEFAULT_INTERVAL_SIZE);
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
        // shaders.properties wins over anything declared in GLSL, matching Umbra's directive precedence.
        resolution = pack.getProperties().getShadowMapResolution().orElse(resolution);
        distance = pack.getProperties().getShadowDistance().isPresent()
                ? pack.getProperties().getShadowDistance().getAsInt() : distance;
        // `const float voxelDistance` overrides the shadow distance for voxelization only: packs that voxelize for
        // colored lighting want a tighter radius than their shadow map covers (Umbra PackShadowDirectives).
        float voxelDistance = parseConstFloat(activeText, "voxelDistance", 0.0f);
        // `shadowDistanceRenderMul` scales the shadow pass's CULLING distance (not the projection). Umbra's unset
        // sentinel is -1, where it falls back to the user's shadow-distance setting; there is no such setting here,
        // so an unset or negative value simply means "no scaling".
        float shadowDistanceRenderMul = parseConstFloat(activeText, "shadowDistanceRenderMul", -1.0f);
        float cullDistance = shadowDistanceRenderMul >= 0.0f ? distance * shadowDistanceRenderMul : distance;
        float voxelRadius = voxelDistance > 0.0f ? voxelDistance : distance;
        com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setVoxelRenderDistanceChunks(
                Math.max(1, Math.round(voxelRadius / 16.0f)));
        parseShadowDepthSamplingSettings(activeText);
        // The FF shadow program (entities/block entities) belongs to the gbuffers custom-texture stage.
        Map<String, Integer> shadowSamplerUnits = new LinkedHashMap<>(GBUFFER_SAMPLER_UNITS);
        shadowSamplerUnits.putAll(gbufferSamplerOverrideUnits());
        try {
            ShadowContentSettings content = ShadowContentSettings.from(pack.getProperties());
            // Umbra's voxelization detection: a shadow geometry stage, or the pack declaring custom images.
            boolean packVoxelizes = shadowSource.get().getGeometrySource().isPresent()
                    || !pack.getProperties().getUmbraCustomImages().isEmpty();
            return new UmbraShadowRenderer(resolution, distance, nearPlane, farPlane, intervalSize, shadowMapFov,
                    sunPathRotation,
                    shadowSource.get(),
                    // Both resolve through the ProgramSet fallback chain, so a pack shipping only `shadow` hands the
                    // renderer the very same ProgramSource for all three and it compiles once.
                    pack.getProgramSet().get(ProgramId.ShadowEntities).orElse(shadowSource.get()),
                    pack.getProgramSet().get(ProgramId.ShadowBlock).orElse(shadowSource.get()),
                    shadowSamplerUnits, this.shaderDefines,
                    this.shadowHardwareFiltering, this.shadowMipmap, this.shadowNearest,
                    this.separateHardwareSamplers, this::bindShaderPackResources, content,
                    voxelDistance, cullDistance, packVoxelizes);
        } catch (Exception e) {
            LOGGER.error("[Umbra] Failed to create the shadow renderer; shadows disabled", e);
            return null;
        }
    }

    // appends one shader stage to both directive scans: raw as authored, active with the pack's own
    // preprocessor conditionals resolved against the macro set that stage compiles with
    // the const directives must be read from active, because a raw first-textual-match happily reads a
    // value the pack disabled
    // Complementary Reimagined declares const int shadowMapResolution = 4096; under
    // #if SHADOW_QUALITY >= 5 || SHADOW_SMOOTHING < 3 and 2048 under its #else; at its default 3/4 the
    // 4096 map we allocated left every texelFetch(shadowtex0, ivec2(pos * shadowMapResolution)) in the
    // pack - its volumetric light shafts, and the scene-aware light-shaft probe - addressing one quadrant
    // of the map
    // that quadrant is mostly cleared depth, which reads as "lit", so light shafts shone straight through
    // terrain; normalized shadow2D lookups are resolution-independent, which is why surface shadows
    // looked correct
    private void appendDirectiveSource(StringBuilder raw, StringBuilder active, ProgramSource source,
                                       Optional<String> stage) {
        if (!stage.isPresent()) {
            return;
        }
        raw.append(stage.get());
        active.append(GlslPreprocessor.resolveConditionals(stage.get(),
                com.bdmajora.impetus.umbra.gl.shader.ShaderMacros.forProgram(this.shaderDefines, source.getName())));
        // Stages are resolved individually, so terminate the appended text: an unterminated construct in one file
        // must not run into the next.
        active.append('\n');
    }

    // the GLSL float literal grammar, as permissive as Float#parseFloat: optional sign, the .5 and 1.
    // forms, and an exponent
    // the narrower -?[0-9]+(\.[0-9]+)? this replaces silently fell back to the default for a pack
    // writing const float x = .5; or 1e-3
    private static final String FLOAT_LITERAL = "([-+]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?)[fF]?";

    // the LAST match of pattern in text, or null
    // last-wins is Umbra's and OptiFine's rule: Umbra dispatches every directive it finds in file order
    // and each overwrites the previous (DispatchingDirectiveHolder), OptiFine likewise assigns per line
    // (Shaders.java:2555)
    // these scans read text concatenated from several stages and programs, so a pack that declares a
    // directive more than once must resolve the same way it does under Umbra
    // first-match-wins was the old behaviour, and is a silent divergence whenever the values differ
    private static String lastMatch(Pattern pattern, String text, int group) {
        Matcher matcher = pattern.matcher(text);
        String value = null;
        while (matcher.find()) {
            value = matcher.group(group);
        }
        return value;
    }

    // const int NAME = value; in shader text
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

    // OptiFine's legacy #define <NAME> <value> spelling of a shadow directive.
    private static int parseDefineInt(String text, String name, int fallback) {
        Matcher matcher = Pattern.compile("(?m)^\\s*#define\\s+" + name + "\\s+(\\d+)").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    // #define NAME value in shader text
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

    // const bool NAME = value;, false when absent
    private static boolean parseConstBool(String text, String name) {
        return parseOptionalConstBool(text, name).orElse(false);
    }

    // const bool with presence distinguished from false
    private static Optional<Boolean> parseOptionalConstBool(String text, String name) {
        String value = lastMatch(Pattern.compile("const\\s+bool\\s+" + name + "\\s*=\\s*(true|false)"), text, 1);
        return value != null ? Optional.of(Boolean.parseBoolean(value)) : Optional.empty();
    }

    // Reads the shadowHardwareFiltering and shadowtex filtering constants
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

    // A shared constant sets both entries, then per-index constants override
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

    // const float NAME = value;
    private static float parseConstFloat(String text, String name, float fallback) {
        Float value = parseConstFloat(text, name);
        return value != null ? value : fallback;
    }

    // the pack's const float <name>, or null when it declares none
    // also accepts const int <name> for the float-valued directives: GLSL would reject the implicit
    // narrowing, but packs write const float shadowDistance = 120; anyway, and both Umbra
    // (Float.parseFloat over the token) and OptiFine (isConstFloat on a value it later parses loosely)
    // tolerate it
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

    // The color buffers the terrain program writes, per its DRAWBUFFERS directive.
    private int[] terrainDrawBuffers(ShaderPack pack) {
        return programDrawBuffers(pack, ProgramId.Terrain, "gbuffers_terrain");
    }

    // one gbuffer program's DRAWBUFFERS mask, resolved against the macros that program actually compiles with
    // the scoping matters: a program whose pre-Umbra branch is active (see
    // ShaderMacros.setPackLegacyPrograms) declares a different mask than its Umbra branch - Sildur's
    // gbuffers_water is 41 with IS_IRIS and 412 without - and a buffer missing from the attachment set
    // gets rerouted to colortex0, corrupting it
    private int[] programDrawBuffers(ShaderPack pack, ProgramId id, String fallbackName) {
        ProgramSource source = pack.getProgramSet().get(id).orElse(null);
        String fragment = source == null ? null : source.getFragmentSource().orElse(null);
        String name = source == null ? fallbackName : source.getName();
        if (fragment == null) {
            return DrawBuffers.DEFAULT.clone();
        }
        return sanitizeDrawBuffers(name, DrawBuffers.parseActive(fragment,
                com.bdmajora.impetus.umbra.gl.shader.ShaderMacros.forProgram(this.shaderDefines, name)));
    }

    // the union of every gbuffer-stage program's DRAWBUFFERS mask (terrain, water, and the fixed-function
    // programs), plus colortex0
    // all of these must be attached to the gbuffer FBOs up front, because a draw-buffer mask naming an
    // attachment without an image makes the FBO incomplete
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
        return result;
    }

    // a gbuffer FBO world rendering is redirected into: every gbuffer-stage colour target attached -
    // writing the current "front" side of each under the given flip state - plus the shared depth texture
    // the draw-buffer mask starts as plain-colour-only; setPhase and the terrain override switch it per
    // program, OptiFine-style
    private UmbraFramebuffer createGbufferFramebuffer(BufferFlipper flipper) {
        UmbraFramebuffer framebuffer = new UmbraFramebuffer();
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

    // Fails loudly with the purpose and attachments named
    private static void checkFramebufferComplete(UmbraFramebuffer framebuffer, String purpose, int[] buffers) {
        int status = framebuffer.getStatus();
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Incomplete Umbra " + purpose + " framebuffer for buffers "
                    + Arrays.toString(buffers) + ": status=" + status);
        }
    }

    // Sets the FBO's draw buffers from a program's logical colortex list
    private void drawGbufferBuffers(UmbraFramebuffer framebuffer, int[] logicalDrawBuffers) {
        int[] physicalDrawBuffers = new int[logicalDrawBuffers.length];
        java.util.Set<Integer> written = new java.util.HashSet<>();
        for (int i = 0; i < logicalDrawBuffers.length; i++) {
            Integer attachmentPoint = this.gbufferAttachmentPoints.get(logicalDrawBuffers[i]);
            if (attachmentPoint == null) {
                LOGGER.warn("[Umbra] Gbuffer draw buffer colortex{} is not attached; routing output slot {} to colortex0",
                        logicalDrawBuffers[i], i);
                attachmentPoint = this.gbufferAttachmentPoints.get(0);
            } else {
                written.add(logicalDrawBuffers[i]);
            }
            physicalDrawBuffers[i] = attachmentPoint == null ? 0 : attachmentPoint;
        }
        // Umbra parity: a gbuffer FBO must hold ONLY the buffers the current program writes, so a program that samples a
        // colortex it doesn't write (gbuffers_terrain reading gaux4=colortex7 for fog) reads a detached — thus valid —
        // texture instead of triggering a feedback loop that returns garbage (the ~50 that blew the horizon white).
        framebuffer.retainColorAttachments(written);
        framebuffer.drawBuffers(physicalDrawBuffers);
    }

    // bakes one frame's ping-pong schedule from the flipper's current state, advancing the flipper as it goes
    // family order matches Umbra: begin, prepare, (gbuffers), deferred, (translucents), composite, final
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
            this.blitSourceFramebuffer = new UmbraFramebuffer();
            this.blitSourceFramebuffer.addColorAttachment(0, frontTexture(flipper, 0));
            this.blitSourceFramebuffer.readBuffer(0);
            checkFramebufferComplete(this.blitSourceFramebuffer, "fallback blit", new int[]{0});
        }
    }

    // schedules one numbered family in index order
    // an entry with a vertex+fragment pair becomes a drawing pass; an entry the pack only supplies .csh
    // files for becomes a compute-only pass (Umbra's ComputeOnlyPass), which is how Photon's
    // deferred4_a.csh gets to run at all - there is no deferred4_a.fsh
    // either way the entry's computes are dispatched before the entry's own draw, under the same flip state
    private void buildFamily(ShaderPack pack, ProgramArrayId id, TextureStage stage, BufferFlipper flipper,
                             List<FullscreenPass> target) {
        for (int i = 0; i < id.getNumPrograms(); i++) {
            Optional<ProgramSource> source = pack.getProgramSet().get(id, i);
            if (!source.isPresent()) {
                continue;
            }
            String name = source.get().getName();
            if (!isProgramEnabled(pack, name)) {
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
                // The draw failed to compile but the computes linked: still run them, as Umbra would.
                target.add(FullscreenPass.computeOnly(name, snapshotFrontTextures(flipper), flipper.snapshot(),
                        computes));
            }
        }
    }

    // gives a pass the viewport of the buffers it writes
    // Umbra throws when a pass mixes differently-sized draw buffers; here the mismatch is logged and the
    // first size wins, because refusing to build the pass would take a whole stage of the chain out
    // rather than render it at a slightly wrong scale
    private void applyPassViewport(FullscreenPass pass, int[] drawBuffers) {
        for (int buffer : drawBuffers) {
            if (buffer < 0 || buffer >= UmbraRenderTargets.MAX_COLOR_BUFFERS
                    || !this.renderTargets.hasCustomSize(buffer)) {
                continue;
            }
            int width = this.renderTargets.getWidth(buffer);
            int height = this.renderTargets.getHeight(buffer);
            if (pass.viewportWidth == 0) {
                pass.viewportWidth = width;
                pass.viewportHeight = height;
            } else if (pass.viewportWidth != width || pass.viewportHeight != height) {
                LOGGER.warn("[Umbra] Pass '{}' writes buffers of different sizes ({}x{} vs colortex{} at {}x{}); "
                                + "using the first", pass.name, pass.viewportWidth, pass.viewportHeight,
                        buffer, width, height);
            }
        }
    }

    // applies scale.<program> to a fullscreen pass
    // separate from applyPassViewport because the two are independent: that one reads the size of the
    // buffers being written, this one is the pack asking for a smaller rasterised rectangle within
    // whatever that size turned out to be - a pass can have both
    private void applyPassViewportScale(FullscreenPass pass, ShaderPack pack) {
        float[] scale = pack.getProperties().getViewportScale(pass.name);
        if (scale == null) {
            return;
        }
        pass.viewportScale = scale[0];
        pass.viewportOffsetX = scale[1];
        pass.viewportOffsetY = scale[2];
    }

    // Honours a pass's flip.<pass>.<buffer> directives before it runs
    private void applyExplicitPreFlips(Map<Integer, Boolean> explicitFlips, BufferFlipper flipper, String name) {
        for (Map.Entry<Integer, Boolean> entry : explicitFlips.entrySet()) {
            if (entry.getValue()) {
                flipper.flip(entry.getKey());
            }
        }
    }

    // Umbra's FinalPassRenderer.SwapPass: every buffer the chain leaves odd-flipped ends the frame with
    // its latest content on the ALT side, so copy alt->main after the final pass
    // buffers cleared at frame start are skipped, matching Umbra
    // do not special-case gbuffer attachments here: packs such as Complementary deliberately mark some
    // gbuffer-written targets (colortex4, for example) as clear=false so their temporal contents survive
    private void buildSwapPasses(BufferFlipper flipper) {
        for (int i = 0; i < UmbraRenderTargets.MAX_COLOR_BUFFERS; i++) {
            if (!flipper.isFlipped(i) || this.renderTargets.get(i) == null || this.colorBufferClears[i]) {
                continue;
            }
            UmbraRenderTarget target = this.renderTargets.get(i);
            UmbraFramebuffer from = new UmbraFramebuffer();
            from.addColorAttachment(0, target.getAltTexture());
            from.readBuffer(0);
            checkFramebufferComplete(from, "swap colortex" + i, new int[]{i});
            this.swapPasses.add(new SwapPass(i, from, target.getMainTexture(),
                    this.renderTargets.getWidth(i), this.renderTargets.getHeight(i)));
        }
    }

    // Schedules the per-frame clears each target needs, with its declared clear colour
    private void buildClearPasses() {
        for (int i = 0; i < UmbraRenderTargets.MAX_COLOR_BUFFERS; i++) {
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

    // Whether a colortex is attached to the gbuffer FBO
    private boolean isGbufferAttachment(int index) {
        return this.gbufferAttachmentPoints.containsKey(index);
    }

    // colortex0 clears to fog colour, the rest to transparent black, unless overridden
    private float[] defaultClearColor(int index) {
        if (this.colorBufferClearColors[index] != null) {
            return this.colorBufferClearColors[index];
        }
        if (index == 0) {
            return null; // Umbra clears colortex0 to the current fog color by default.
        }
        if (index == 1) {
            return new float[]{1.0f, 1.0f, 1.0f, 1.0f};
        }
        return new float[]{0.0f, 0.0f, 0.0f, 0.0f};
    }

    // the custom-texture stage a full-screen program belongs to, from its source name: beginN -> begin,
    // prepareN -> prepare, deferredN -> deferred, compositeN and final -> composite
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

    // For debug logging
    private static String formatBitSet(BitSet bitSet) {
        List<Integer> values = new ArrayList<>();
        for (int bit = bitSet.nextSetBit(0); bit >= 0; bit = bitSet.nextSetBit(bit + 1)) {
            values.add(bit);
        }
        return values.toString();
    }

    // For debug logging
    private static String formatClearColor(float[] color) {
        return color == null ? "fog" : Arrays.toString(color);
    }

    // For debug logging
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

    // compiles a fullscreen program and its uniforms once, caching it by source name
    // returns null if the program failed to compile, cached as absent so we do not retry
    private UmbraProgram cachedProgram(ProgramSource source) {
        String name = source.getName();
        if (this.compiledPrograms.containsKey(name)) {
            return this.compiledPrograms.get(name);
        }
        UmbraProgram program = compileFullscreenProgram(source, fullscreenTextureStage(name));
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
            UmbraProgram program = cachedProgram(source);
            if (program == null) {
                return null;
            }
            int[] drawBuffers = sanitizeCompositeDrawBuffers(name, program.getDrawBuffers());
            Map<Integer, Boolean> explicitFlips = pack.getProperties().getExplicitFlips(name);
            BitSet flipsBefore = flipper.snapshot();
            BitSet mipmappedBuffers = parseMipmappedBuffers(source);

            // Reads see the current "front" side; the FBO writes the back side; then the written buffers flip.
            int[] colorSamplers = snapshotFrontTextures(flipper);
            UmbraFramebuffer framebuffer = this.renderTargets.createColorFramebuffer(drawBuffers);
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

            FullscreenPass pass = new FullscreenPass(name, program, this.compiledUniforms.get(name), framebuffer,
                    colorSamplers, drawBuffers, ProgramBlendState.from(pack.getProperties(), name),
                    flipsBefore, flipsAfter, mipmappedBuffers, computes);
            applyPassViewport(pass, drawBuffers);
            applyPassViewportScale(pass, pack);
            return pass;
        } catch (Exception e) {
            LOGGER.error("[Umbra] Failed to build pass '{}'; it will be skipped: {}", name, e.getMessage());
            return null;
        }
    }

    // The final program, which writes the main framebuffer rather than a colortex
    private FullscreenPass buildFinalPass(ShaderPack pack, BufferFlipper flipper) {
        Optional<ProgramSource> source = pack.getProgramSet().get(ProgramId.Final);
        if (!source.isPresent()) {
            return null;
        }
        String name = source.get().getName();
        if (!isProgramEnabled(pack, name)) {
            return null;
        }
        try {
            UmbraProgram program = cachedProgram(source.get());
            if (program == null) {
                return null;
            }
            BitSet flips = flipper.snapshot();
            BitSet mipmappedBuffers = parseMipmappedBuffers(source.get());
            int[] colorSamplers = snapshotFrontTextures(flipper);
            return new FullscreenPass(name, program, this.compiledUniforms.get(name), null,
                    colorSamplers, DrawBuffers.DEFAULT.clone(),
                    ProgramBlendState.from(pack.getProperties(), name), flips, (BitSet) flips.clone(),
                    mipmappedBuffers, java.util.Collections.<ComputePass>emptyList());
        } catch (Exception e) {
            LOGGER.error("[Umbra] Failed to build final pass; falling back to colortex0 blit: {}", e.getMessage());
            return null;
        }
    }

    // the colortexNMipmapEnabled set for one pass
    // per program, like Umbra's ProgramDirectives, and read from the fragment stage with the pack's
    // conditionals resolved - see activeDirectiveStages for why raw source is not safe here (Body Camera
    // declares colortex0MipmapEnabled true and false in the two branches of one #if, and the dead branch
    // was winning, which broke its auto-exposure metering)
    private BitSet parseMipmappedBuffers(ProgramSource source) {
        BitSet mipmappedBuffers = new BitSet(UmbraRenderTargets.MAX_COLOR_BUFFERS);
        Optional<String> fragmentSource = source.getFragmentSource();
        if (!fragmentSource.isPresent()) {
            return mipmappedBuffers;
        }

        Matcher matcher = MIPMAP_DIRECTIVE.matcher(GlslPreprocessor.resolveConditionals(fragmentSource.get(),
                com.bdmajora.impetus.umbra.gl.shader.ShaderMacros.forProgram(this.shaderDefines, source.getName())));
        while (matcher.find()) {
            Integer index = colorTargetIndex(matcher.group(1));
            if (index == null || index >= UmbraRenderTargets.MAX_COLOR_BUFFERS) {
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

    // colortexN or gcolor-style sampler name to an index; null for anything else
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

    // Transforms and compiles one composite-family program
    private UmbraProgram compileFullscreenProgram(ProgramSource source, TextureStage stage) {
        String vshRaw = source.getVertexSource().orElse(null);
        String fshRaw = source.getFragmentSource().orElse(null);
        if (vshRaw == null || fshRaw == null) {
            LOGGER.warn("[Umbra] Program '{}' is missing a vertex or fragment stage; skipping", source.getName());
            return null;
        }
        // Raw texture.<stage>.<sampler> directives redirect the identifier to its minted customtexN name, but only
        // where the declared sampler type matches the directive's target. Must precede everything else so the later
        // transforms and the DRAWBUFFERS parse all see the final identifier set.
        vshRaw = CustomTextureTransformer.transform(source.getName(), vshRaw, stage);
        fshRaw = CustomTextureTransformer.transform(source.getName(), fshRaw, stage);
        // Modern (1.17+) attribute/matrix names -> fixed-function built-ins. The full-screen quad is drawn with the
        // ortho projection pushed in runPass, so gl_ProjectionMatrix is exactly the (0,1)->(-1,1) matrix Umbra
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
            // Scoped per pass, same as the terrain path: a pass listed in `impetus.umbra.legacyPrograms` compiles
            // without IS_IRIS, and the one map drives both the DRAWBUFFERS parse and the injected prologue.
            Map<String, String> macros = com.bdmajora.impetus.umbra.gl.shader.ShaderMacros.forProgram(
                    this.shaderDefines, source.getName());
            int[] drawBuffers = sanitizeCompositeDrawBuffers(source.getName(), DrawBuffers.parseActive(fshRaw, macros));
            String vsh;
            String fsh;
            if (modern) {
                // Modern sources rely on the driver preprocessor for their #if trees; the MC_*/IRIS_FEATURE_* macro
                // environment has to be present for those gates (colored lighting checks IRIS_FEATURE_CUSTOM_IMAGES).
                // The quad is drawn from generic vertex attributes, so gl_MultiTexCoord0 has to be fed from a real
                // one rather than relying on NVIDIA's generic-to-conventional aliasing. See
                // ModernPackTransformer#bindFullscreenTexCoord.
                vsh = ModernPackTransformer.bindFullscreenTexCoord(
                        ModernPackTransformer.transform(stabilizeShaderSource(source.getName(),
                                com.bdmajora.impetus.umbra.gl.shader.ShaderMacros.injectDefines(vshRaw, macros))));
                fsh = DrawBuffers.rewriteFragmentOutputs(ModernPackTransformer.transform(
                        stabilizeShaderSource(source.getName(),
                                com.bdmajora.impetus.umbra.gl.shader.ShaderMacros.injectDefines(fshRaw, macros))),
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
                        com.bdmajora.impetus.umbra.gl.shader.ShaderMacros.injectDefines(vshRaw, macros)));
                fsh = FullscreenTransformer.transformFragmentShader(foldUncompilableConditionals(source.getName(),
                        com.bdmajora.impetus.umbra.gl.shader.ShaderMacros.injectDefines(fshRaw, macros)), drawBuffers);
            }
            vertex = new GlShader(ShaderType.VERTEX, source.getName() + ".vsh", vsh);
            fragment = new GlShader(ShaderType.FRAGMENT, source.getName() + ".fsh", fsh);

            ProgramBuilder builder = ProgramBuilder.begin(source.getName())
                    .attach(vertex)
                    .attach(fragment)
                    .bindAttributeLocation(FullscreenQuadRenderer.POSITION_SLOT, "a_Position")
                    .bindAttributeLocation(FullscreenQuadRenderer.TEXCOORD_SLOT, "a_TexCoord")
                    // Modern (compatibility-profile) sources read the quad's texcoord through this instead, since
                    // gl_MultiTexCoord0 cannot be fed portably via generic attribute 8.
                    .bindAttributeLocation(FullscreenQuadRenderer.TEXCOORD_SLOT,
                            ModernPackTransformer.FULLSCREEN_TEXCOORD_ATTRIBUTE);
            if (!modern) {
                // The generated 330-core path writes to an explicit out array whose indices are the dense
                // draw-buffer slots, matching Umbra's packed framebuffer attachments.
                builder.bindFragmentDataLocation(0, "iris_FragData");
            }
            GlProgram program = builder.link();

            assignSamplerUnits(program, stage);
            return new UmbraProgram(program, drawBuffers);
        } finally {
            if (vertex != null) {
                vertex.destroy();
            }
            if (fragment != null) {
                fragment.destroy();
            }
        }
    }

    // points every sampler uniform the program declares at its fixed texture unit (OptiFine's
    // setProgramUniform1i), with the pack's custom-texture overrides for the program's stage applied
    // a colortex override is skipped once an earlier pass in the chain has written (flipped) that buffer,
    // matching Umbra's deactivation rule
    private void assignSamplerUnits(GlProgram program, TextureStage stage) {
        program.bind();
        assignSamplerUnits(program.getGlId(), samplerUnitsForStage(stage), mergedStageOverrides(stage),
                this.flippedAtLeastOnce);
        program.unbind();
    }

    // assigns the standard sampler-unit mapping on the *currently bound* program (raw GL id), with the
    // active pipeline's gbuffers/shadow-stage custom-texture overrides
    // used by the Impetus terrain and shadow overrides, whose program objects are Impetus's rather than
    // ours - both belong to the gbuffers texture stage
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
                // UmbraSamplers.addShadowSamplers parity: when watershadow is present, the legacy shadow alias reads
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

    // Distinguishes the two fixed layouts by a marker name
    private static boolean isGbufferSamplerLayout(Map<String, Integer> samplerUnits) {
        return samplerUnits.getOrDefault("depthtex0", -1) == GBUFFER_DEPTH_TEX_0_UNIT;
    }

    // The gbuffers-stage overrides flattened to name → unit, for GbufferPrograms' sampler table.
    private Map<String, Integer> gbufferSamplerOverrideUnits() {
        Map<String, Integer> units = new LinkedHashMap<>();
        for (Map.Entry<String, CustomTextureManager.Override> entry
                : this.customTextureManager.getOverrides(TextureStage.GBUFFERS_AND_SHADOW).entrySet()) {
            units.put(entry.getKey(), entry.getValue().unit);
        }
        units.putAll(this.customImageManager.getUniformOverrides());
        return units;
    }

    // Layout per stage; composite and final share, gbuffer differs
    private static Map<TextureStage, Map<String, Integer>> samplerUnitsByStage() {
        Map<TextureStage, Map<String, Integer>> byStage = new java.util.EnumMap<>(TextureStage.class);
        for (TextureStage stage : TextureStage.values()) {
            byStage.put(stage, samplerUnitsForStage(stage));
        }
        return byStage;
    }

    // Layout for one stage
    private static Map<String, Integer> samplerUnitsForStage(TextureStage stage) {
        return stage == TextureStage.GBUFFERS_AND_SHADOW ? GBUFFER_SAMPLER_UNITS : FULLSCREEN_SAMPLER_UNITS;
    }

    // the stage's custom-texture overrides plus the (stage-independent) custom-image uniform assignments,
    // in the Override form assignSamplerUnits consumes
    // image entries never deactivate (colorTarget -1)
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

    // Registers every uniform the pipeline can supply against one program
    private static ProgramUniforms buildUniforms(String name, UmbraProgram program) {
        ProgramUniforms.Builder builder = ProgramUniforms.builder(name, program.getProgram().getGlId());
        CommonUniforms.addCommonUniforms(builder);
        MatrixUniforms.addMatrixUniforms(builder);
        com.bdmajora.impetus.umbra.uniforms.custom.ActiveCustomUniforms.assignTo(builder);
        return builder.buildUniforms();
    }

    // The texture currently readable ("front") for each existing color target under the given flip state.
    private int[] snapshotFrontTextures(BufferFlipper flipper) {
        int[] samplers = new int[UmbraRenderTargets.MAX_COLOR_BUFFERS];
        for (int i = 0; i < samplers.length; i++) {
            samplers[i] = this.renderTargets.get(i) == null ? 0 : frontTexture(flipper, i);
        }
        return samplers;
    }

    // The texture a pass reads for a colortex
    private int frontTexture(BufferFlipper flipper, int index) {
        UmbraRenderTarget target = this.renderTargets.getOrCreate(index);
        return flipper.isFlipped(index) ? target.getAltTexture() : target.getMainTexture();
    }

    // Same, from a snapshotted flip state
    private int frontTexture(BitSet flips, int index) {
        UmbraRenderTarget target = this.renderTargets.getOrCreate(index);
        return flips.get(index) ? target.getAltTexture() : target.getMainTexture();
    }

    // The texture a pass writes for a colortex
    private int backTexture(BitSet flips, int index) {
        UmbraRenderTarget target = this.renderTargets.getOrCreate(index);
        return flips.get(index) ? target.getMainTexture() : target.getAltTexture();
    }

    // gbuffer-program draw buffers: shader packs address logical colortex indices, while the shared
    // gbuffer FBO maps those logical targets onto dense physical attachment points
    // called by the terrain override and the phase compiler
    public static int[] sanitizeDrawBuffers(String name, int[] drawBuffers) {
        return sanitizeDrawBuffers(name, drawBuffers, GBUFFER_ATTACHMENT_LIMIT);
    }

    // Composite/deferred passes pack attachments densely, so any colortex0..15 index is fine.
    private static int[] sanitizeCompositeDrawBuffers(String name, int[] drawBuffers) {
        return sanitizeDrawBuffers(name, drawBuffers, UmbraRenderTargets.MAX_COLOR_BUFFERS);
    }

    // Drops out-of-range targets, logging each with the program name
    private static int[] sanitizeDrawBuffers(String name, int[] drawBuffers, int maxExclusive) {
        // An out-of-range index is normally just an inactive #ifdef path in the pack, so it is dropped silently
        // rather than reported; the consumer stays because sanitize requires one
        return DrawBuffers.sanitize(drawBuffers, maxExclusive, buffer -> { });
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

    // renderWorld HEAD: redirect the frame into the gbuffer
    // vanilla's own fog-coloured clear inside renderWorldPass then clears our attachments, and every
    // world draw lands in the render targets
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
        com.bdmajora.impetus.umbra.uniforms.custom.ActiveCustomUniforms.update();

        // noisetex and the stub shadow maps ride along for the whole frame (gbuffer + fullscreen stages) on their
        // fixed units; vanilla never binds units above 1, and GlStateManager's 8-slot cache can't address them.
        // A pack-supplied texture.noise replaces the generated noise (Umbra CustomTextureManager parity).
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

        UmbraFramebuffer gbuffer = this.gbufferFramebuffer;
        this.currentGbuffer = gbuffer;
        runClearPasses(this.fullClearRequired ? this.fullClearPasses : this.clearPasses);
        this.fullClearRequired = false;
        // Umbra beginLevelRendering: the setup computes run once (on the first frame the pipeline is used), then the
        // `begin` family runs every frame on the freshly cleared targets, before any geometry.
        if (!this.setupDispatched) {
            this.setupDispatched = true;
            if (!this.setupPasses.isEmpty()) {
                bindShaderPackResources();
                for (FullscreenPass pass : this.setupPasses) {
                    bindRenderTargetImages(pass);
                    dispatchComputes(pass.computes);
                }
                LWJGL.glUseProgram(0);
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

    // sky phases render at the far plane: packs like LIGHT write BLACK from gbuffers_skybasic and repaint
    // the entire sky procedurally in composite, which only works if the vanilla sky dome - real geometry
    // ~16 blocks above the camera - never reaches depthtex0, because otherwise composite classifies the
    // dome as terrain and passes the black through
    // under OptiFine the sky never lands in the depth buffer; emulate that with glDepthRange(1,1), which
    // is immune to vanilla's own depthMask toggling inside renderSky
    private boolean skyAtFarPlane;

    // The alphaTest.<program> override currently forced on the GL state, so it can be undone.
    private com.bdmajora.impetus.umbra.gl.blending.ProgramAlphaTest activeAlphaTest;

    // true when the pack supplies a program for this phase, so a draw made in it lands in that program's
    // DRAWBUFFERS rather than in vanilla's fixed-function output
    // callers that have to suppress a vanilla GL state - blending into a packed gbuffer, say - use this
    // to leave the fixed-function path untouched
    public boolean hasGbufferProgram(ProgramId phase) {
        return this.worldRenderingActive && this.gbufferPrograms != null && this.gbufferPrograms.get(phase) != null;
    }

    // whether the pack ships this phase's program itself, as opposed to the phase merely resolving
    // through OptiFine's fallback chain onto some other program that was never written with this
    // geometry in mind
    public boolean hasDirectGbufferProgram(ProgramId phase) {
        return this.worldRenderingActive && this.gbufferPrograms != null && this.gbufferPrograms.hasDirect(phase);
    }

    // Whether the frame is between the deferred and composite stages
    public boolean isRenderingPostDeferredTranslucents() {
        return this.worldRenderingActive && !this.deferredPasses.isEmpty()
                && this.currentGbuffer == this.translucentGbufferFramebuffer;
    }

    // Umbra's chain is gbuffers_entities_translucent -> gbuffers_entities -> gbuffers_textured_lit
    // falling straight through to TexturedLit skipped the middle link, so a pack shipping
    // gbuffers_entities but no _translucent variant (most of them) drew translucent entities with the
    // generic textured program and lost whatever the entity program does with entityColor, normals and
    // diffuse lighting
    public ProgramId getTranslucentEntityPhase() {
        return hasGbufferProgram(ProgramId.EntitiesTrans) ? ProgramId.EntitiesTrans : ProgramId.Entities;
    }

    // the gbuffer phase currently bound, or null when none is
    public ProgramId getCurrentPhase() {
        return this.currentPhase;
    }

    // whether the camera pass is drawing right now
    // narrower than isWorldRenderingActive() and the correct test for anything that wants to observe or
    // affect the gbuffer: the shadow pass renders entities and block entities through the SAME vanilla
    // renderers as the camera pass, so worldRenderingActive alone is true for both
    // it also runs first in the frame, so a budgeted hook gated only on worldRenderingActive is spent
    // entirely on shadow draws and never observes the camera pass at all - measured: 48 of 48 probe
    // samples came back shadow=true
    public boolean isCameraPassActive() {
        return this.worldRenderingActive && !UmbraShadowRenderer.isShadowPass();
    }

    // switches the active gbuffer program for a fixed-function world-render phase - sky, entities,
    // particles, weather, clouds, hand - anchored on vanilla's profiler sections
    // binds the pack's program for that phase and points the gbuffer's draw-buffer mask at the program's
    // DRAWBUFFERS; with no pack program the phase renders plain fixed-function into colortex0 only
    // also (re)binds the gbuffer, which heals the redirection if something - the entity-outline
    // framebuffer, say - rebound vanilla's framebuffer mid-frame
    public void setPhase(ProgramId phase) {
        setPhase(phase, defaultRenderStage(phase));
    }

    // Umbra's WorldRenderingPhase ordinals for the phases this pipeline can identify, published as the
    // renderStage uniform
    // Umbra sets one for every draw; before this, only the hand and terrain paths did, so every sky,
    // cloud, weather, entity and outline draw reported MC_RENDER_STAGE_NONE
    // that is not a cosmetic gap: Clarity's gbuffers_skybasic draws stars only under
    // renderStage == MC_RENDER_STAGE_STARS, so its star field could never appear - the star quads were
    // painted with the plain sky gradient instead
    // only the unambiguous phases are mapped; anything else reports NONE rather than guess at the pack
    private static int defaultRenderStage(ProgramId phase) {
        if (phase == null) {
            return 0; // MC_RENDER_STAGE_NONE
        }
        switch (phase) {
            case SkyBasic: return 1;    // MC_RENDER_STAGE_SKY
            case SkyTextured: return 4; // MC_RENDER_STAGE_SUN (vanilla draws sun then moon under one anchor)
            // Umbra has no distinct phase for the eyes overlay; it draws inside the entity pass.
            case Entities: case SpiderEyes: return 11; // MC_RENDER_STAGE_ENTITIES
            case DamagedBlock: return 13; // MC_RENDER_STAGE_DESTROY
            case Line: return 14;       // MC_RENDER_STAGE_OUTLINE
            case Particles: return 19;  // MC_RENDER_STAGE_PARTICLES
            case Clouds: return 20;     // MC_RENDER_STAGE_CLOUDS
            case Weather: return 21;    // MC_RENDER_STAGE_RAIN_SNOW
            default: return 0;
        }
    }

    // mirrors OptiFine's Shaders.enableLightmap()/disableLightmap(), which swap program 2
    // (gbuffers_textured) and program 3 (gbuffers_textured_lit) whenever vanilla toggles the lightmap
    // texture unit:
    //   enableLightmap()  { lightmapEnabled = true;  if (activeProgram == 2) useProgram(3); }
    //   disableLightmap() { lightmapEnabled = false; if (activeProgram == 3) useProgram(2); }
    // without this, geometry drawn while unit 1 is disabled still runs gbuffers_textured_lit, whose
    // texture2D(lightmap, lmcoord) then samples a disabled unit and reads white - every such surface
    // renders fullbright
    // it bites items hardest because DefaultVertexFormats.ITEM carries no lightmap element at all, so
    // item quads inherit whatever gl_MultiTexCoord1 and unit-1 state the last caller left behind
    // (RenderItemFrame#renderItem goes straight to RenderItem under
    // RenderHelper.enableStandardItemLighting(), which does not touch the lightmap unit)
    // servers that build scenery out of custom item models in item frames therefore light up while
    // ordinary terrain stays correct
    // only the Textured/TexturedLit pair moves, exactly as in OptiFine - a phase like Entities or Terrain
    // is left alone, because those programs are selected by the geometry being drawn rather than by the
    // lightmap toggle
    public void setLightmapEnabled(boolean enabled) {
        if (!this.worldRenderingActive) {
            return;
        }
        ProgramId target;
        if (enabled) {
            target = this.currentPhase == ProgramId.Textured ? ProgramId.TexturedLit : null;
        } else {
            target = this.currentPhase == ProgramId.TexturedLit ? ProgramId.Textured : null;
        }
        if (target == null) {
            return;
        }
        // Carry the current render stage across rather than letting setPhase(ProgramId) recompute the default.
        // OptiFine's useProgram() only swaps the program and leaves its stage tracking alone, and this pair maps to
        // MC_RENDER_STAGE_NONE by default — so recomputing would silently discard a stage a caller set explicitly
        // (see setPhase(ProgramId, int): gbuffers_textured_lit carries both particles and translucent entities, whose
        // stage can only come from the call site).
        setPhase(target, CapturedRenderingState.INSTANCE.getRenderStage());
    }

    // Overload for callers that know a finer phase than ProgramId can express (sky basic covers sky/stars/void).
    public void setPhase(ProgramId phase, int renderStage) {
        // The shadow pass owns the GL state for its own framebuffer: UmbraShadowRenderer binds the `shadow` program,
        // its own draw buffers and its own blend state, and never calls this method. Letting a gbuffer phase be
        // selected while it runs binds a gbuffer program over the shadow program and re-points the draw buffers at
        // the gbuffer attachments, corrupting the shadow map for the rest of the frame — and the shadow map feeds
        // every lit surface, so the damage is world-wide rather than local to whatever was being drawn.
        //
        // This is reachable because the shadow pass renders entities and block entities through the SAME vanilla
        // renderers as the camera pass (UmbraShadowRenderer#renderEntityShadows -> RenderManager#renderEntityStatic),
        // so any per-object hook on those paths fires in both. Measured: a per-entity setPhase on that path shredded
        // water and terrain shading. Guarding here rather than at each call site means one missed caller cannot do
        // it again — which is exactly why the neighbouring beginEyes/endEyes/armor-glint methods all carry the same
        // isShadowPass() test.
        if (!this.worldRenderingActive || UmbraShadowRenderer.isShadowPass()) {
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

    // GL_CURRENT_PROGRAM, spelled as a literal because the generated GL constant classes do not carry it
    private static final int GL_CURRENT_PROGRAM = 0x8B8D;

    // re-uploads the current phase's DYNAMIC uniforms without repeating the rest of setPhase's state work
    // a phase is selected once and then covers a whole batch of draws - "entities" is set once for every
    // entity in the frame - so anything that varies per draw inside a batch would otherwise never reach
    // the GPU after the phase began
    // entityColor is one case that needs it: the hurt flash belongs to one entity, not to the batch
    // entityId, blockEntityId and currentRenderedItemId are the others, and they are the reason this is
    // called from the three id mixins rather than only from the hurt-flash one
    // Umbra never faces this: EntityPatcher#patchEntityId *deletes* the
    // uniform int entityId/blockEntityId/currentRenderedItemId declarations, rewrites every reference to
    // iris_entityInfo.x/.y/.z, and feeds that from the per-vertex attribute in ivec3 iris_Entity
    // per-vertex data cannot latch, because each vertex carries its own ids (the ONCE/-1 uniforms Umbra
    // keeps in CommonUniforms are warning-suppression dummies for programs where the attribute is not
    // wired up, not the real values)
    // 1.12's fixed-function vertex formats have nowhere to put an extra integer attribute, so Impetus
    // keeps them as genuine uniforms, and uploading them per object is what restores the per-draw
    // semantics the attribute gives Umbra for free
    // left un-refreshed, one id latches for a whole batch: whichever object happened to be current when
    // the phase was entered decides the material for every draw after it
    // packs dispatch on exactly these values - Complementary's gbuffers_entities opens
    // int mat = currentRenderedItemId; - so a latched emissive id makes the entire batch emissive, and
    // since draw order tracks the camera, which id wins changes with the heading
    // cheap enough to call per object only because it goes through ProgramUniforms#updatePerObject(),
    // which touches just the ids and entityColor
    // it must NOT be widened back to the whole DYNAMIC set: MatrixUniform and Matrix3Uniform have no
    // dirty-check at all, and five of the six DYNAMIC matrix suppliers each issue a
    // glGetFloat(GL_MODELVIEW_MATRIX) pipeline query plus a direct buffer allocation - at two calls per
    // object that is tens of thousands of stalling queries a frame
    // unlike setPhase, this touches nothing but uniforms - no vertex arrays, draw buffers, blend or alpha
    // test - which is what makes it safe on a per-object hook that setPhase was not
    // no-op in the shadow pass, which binds its own entity program that this phase tracking does not describe
    public void refreshDynamicUniforms() {
        if (!this.worldRenderingActive || UmbraShadowRenderer.isShadowPass() || this.currentPhase == null
                || this.gbufferPrograms == null) {
            return;
        }
        GbufferPrograms.Entry entry = this.gbufferPrograms.get(this.currentPhase);
        if (entry == null) {
            return;
        }
        // glUniform* writes into whatever program is bound RIGHT NOW, and uniform locations are per-program. The
        // phase field only records the last setPhase, while the per-object id hooks fire from vanilla renderers that
        // have no idea which program that was — a held item reaches RenderItem#renderItem underneath the hand pass,
        // not the entity phase. Pushing the recorded phase's Uniform objects while a different program is bound would
        // write at locations belonging to the other program and silently corrupt whatever those slots mean there.
        // Ask GL what is actually bound rather than trusting the bookkeeping to have been kept in step.
        if (LWJGL.glGetInteger(GL_CURRENT_PROGRAM) != entry.getProgram().getProgram().getGlId()) {
            return;
        }
        entry.getUniforms().updatePerObject();
    }

    // the "eyes" overlay layers - spider, enderman and ender dragon - which is what gbuffers_spidereyes
    // is for
    // OptiFine brackets the same three draws with Shaders.beginSpiderEyes()/endSpiderEyes(); Umbra routes
    // them through ShaderKey.ENTITIES_EYES
    // the lightmap coordinate has to be rewritten here: 1.12 signals "full bright" for these layers by
    // pushing the raw sentinel OpenGlHelper.setLightmapTextureCoords(unit, 61680, 0), which only works
    // because GL_CLAMP pins the lightmap *texture lookup* to the brightest texel
    // a shader consumes the same value arithmetically - gl_TextureMatrix[1] * gl_MultiTexCoord1 is
    // (61680 + 8) / 256 = 240.97, i.e. 256x outside the [0,1] range every pack assumes
    // packs raise that coordinate to a power (Mellow uses pow(lm.x, 2.4), so ~6e5), which overflows an
    // R11F_G11F_B10F colortex and leaves an Inf/NaN pixel wrapped in an enormous bloom halo
    // modern Minecraft has no such sentinel - its full-bright packed light is 0xF000F0 - which is why
    // Umbra's VanillaTransformer substitutes a literal vec4(240.0, 240.0, 0.0, 1.0) for
    // gl_MultiTexCoord1 on every FULLBRIGHT draw; do the same, from the GL side
    // no-op in the shadow pass: Umbra maps the eyes render type to the shadow entity program there, not
    // to gbuffers_spidereyes
    public void beginEyes() {
        if (!this.worldRenderingActive || UmbraShadowRenderer.isShadowPass()) {
            return;
        }
        this.phaseBeforeEyes = this.currentPhase;
        this.phaseBeforeEyesStage = CapturedRenderingState.INSTANCE.getRenderStage();
        LWJGL.glMultiTexCoord2f(OpenGlHelper.lightmapTexUnit, FULL_BRIGHT_LIGHTMAP_COORD, FULL_BRIGHT_LIGHTMAP_COORD);
        setPhase(ProgramId.SpiderEyes);
    }

    // back to the entity program, as OptiFine's endSpiderEyes does - except that the eyes layers also run
    // in the post-translucent entity batch, where the phase is gbuffers_entities_translucent
    // restore what was actually bound instead of assuming, so a pack with a dedicated translucent-entity
    // program keeps it for the rest of the batch
    // vanilla puts the real lightmap coordinate back itself on the line after the model draw
    public void endEyes() {
        if (!this.worldRenderingActive || UmbraShadowRenderer.isShadowPass()) {
            return;
        }
        setPhase(this.phaseBeforeEyes, this.phaseBeforeEyesStage);
    }

    // routes the enchantment glint through gbuffers_armor_glint, matching OptiFine's
    // ShadersRender.renderEnchantedGlintBegin (Shaders.useProgram(17))
    // without this the glint inherits whichever program is current - gbuffers_entities for armour, the
    // hand program for a held item - so a pack that ships gbuffers_armor_glint to give the glint its own
    // additive treatment never gets it, and the glint is shaded as if it were the entity's own surface
    // two gates, both of which fall out of the existing state rather than needing the OptiFine-only
    // renderItemGui flag: worldRenderingActive is false while the GUI draws inventory items, and the
    // shadow pass maps every entity draw to the shadow program (OptiFine likewise skips the glint there)
    public void beginArmorGlint() {
        if (!this.worldRenderingActive || UmbraShadowRenderer.isShadowPass()) {
            return;
        }
        this.phaseBeforeArmorGlint = this.currentPhase;
        this.phaseBeforeArmorGlintStage = CapturedRenderingState.INSTANCE.getRenderStage();
        this.armorGlintActive = true;
        setPhase(ProgramId.ArmorGlint);
    }

    // restores whatever was bound before the glint, the same way endEyes() does: the glint runs inside
    // both the entity batch and the first-person hand batch, so assuming gbuffers_entities would strand
    // the hand
    // OptiFine's renderEnchantedGlintEnd makes the same distinction explicitly
    public void endArmorGlint() {
        if (!this.armorGlintActive) {
            return;
        }
        this.armorGlintActive = false;
        setPhase(this.phaseBeforeArmorGlint, this.phaseBeforeArmorGlintStage);
    }

    // The phase #setPhase(ProgramId, int) last selected, so #endEyes() can put it back.
    private ProgramId currentPhase;
    private ProgramId phaseBeforeEyes;
    private int phaseBeforeEyesStage;
    private ProgramId phaseBeforeArmorGlint;
    private int phaseBeforeArmorGlintStage;
    // Guards #endArmorGlint() so a begin that bailed out (GUI, shadow pass) cannot restore a stale phase.
    private boolean armorGlintActive;

    // port of OptiFine's Shaders.drawHorizon (preSkyList): draws an octagonal ring at the render-distance
    // edge, from ground level (y = -cameraY) up to y = 16, through the currently-bound sky program
    // (gbuffers_skybasic)
    // vanilla's renderSky only draws the sky disc above the horizon and the void plane well below it, so
    // the thin band at the horizon between the render-distance edge and those is left uncovered
    // because packs commonly set colortex1 to not clear (OptiFine and Umbra both honour that), those
    // uncovered pixels keep last frame's colortex1 - which self-perpetuated into the blown ~50 neutral
    // horizon band
    // skybasic derives the sky colour from the view direction rather than vertex colour, so this fill
    // gets the correct atmospheric horizon colour and the band disappears
    // call right before the sky disc, matching OptiFine
    public void drawSkyHorizon() {
        if (!this.worldRenderingActive || this.skyHorizonActive) {
            return;
        }
        this.skyHorizonActive = true;
        try {
            // Clamp the radius (Umbra HorizonRenderer parity): at high render distances the ring would otherwise reach
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
            LOGGER.warn("[Umbra] drawSkyHorizon failed: {}", t.toString());
        } finally {
            this.skyHorizonActive = false;
        }
    }

    private boolean skyHorizonActive;

    // called when the Impetus terrain override program binds (UmbraTerrainShaderInterface.setupState):
    // points the gbuffer's draw-buffer mask at the terrain/water program's DRAWBUFFERS directive
    public void onTerrainDraw(int[] drawBuffers, ProgramBlendState blendState) {
        onTerrainDraw(drawBuffers, blendState,
                com.bdmajora.impetus.umbra.gl.blending.ProgramAlphaTest.empty(), false);
    }

    public void onTerrainDraw(int[] drawBuffers, ProgramBlendState blendState,
                              com.bdmajora.impetus.umbra.gl.blending.ProgramAlphaTest alphaTest,
                              boolean translucentPass) {
        if (!this.worldRenderingActive) {
            return;
        }
        int[] sanitizedDrawBuffers = DrawBuffers.sanitize(drawBuffers, GBUFFER_ATTACHMENT_LIMIT);
        // The chunk renderer owns atlas/lightmap setup, but shader-pack samplers are Umbra-owned dynamic bindings.
        // Re-assert them at program use, matching Umbra' sampler model and OptiFine's repeated program-uniform/binding
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

    // Blend state for the opaque terrain pass on every attachment
    private static void restoreGbufferOpaqueBlend(int[] drawBuffers) {
        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
        disableIndexedBlend(drawBuffers.length);
    }

    // Blend state for the translucent terrain pass
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

    // Turns blending off per attachment after a pass that set it per attachment
    private static void disableIndexedBlend(int drawBufferSlots) {
        if (!LWJGL.supportsBufferBlending()) {
            return;
        }
        int maxSlots = Math.min(drawBufferSlots, LWJGL.glGetInteger(GL30.GL_MAX_DRAW_BUFFERS));
        for (int slot = 0; slot < maxSlots; slot++) {
            LWJGL.glDisablei(GL11.GL_BLEND, slot);
        }
    }

    // Hook for the chunk renderer after a terrain pass; resets indexed blend
    public void afterTerrainDraw(int drawBufferSlots) {
        if (!this.worldRenderingActive || UmbraShadowRenderer.isShadowPass()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        mc.getFramebuffer().bindFramebuffer(true);
        restoreMainDrawReadBuffers(mc);
        disableIndexedBlend(drawBufferSlots);
        GlTextureUnits.resetToUnit0();
    }

    // called right after setupCameraTransform / ActiveRenderInfo.updateRenderInfo: vanilla has just read
    // the camera matrices back into ActiveRenderInfo's buffers, so copy them for the uniform providers
    public void captureRenderingState() {
        if (!this.worldRenderingActive) {
            return;
        }
        CapturedRenderingState.INSTANCE.setGbufferModelView(new Matrix4f(ActiveRenderInfoAccessor.getModelViewMatrix()));
        CapturedRenderingState.INSTANCE.setGbufferProjection(new Matrix4f(ActiveRenderInfoAccessor.getProjectionMatrix()));
    }

    // renders the shadow map for this frame
    // called right after captureRenderingState, when the shadow angle and camera position are current and
    // nothing has drawn into the gbuffer yet, then re-points GL at the gbuffer and puts the fresh shadow
    // map on the shadowtex0/1 units, replacing the always-lit stub bound at frame start
    // the prepare family runs here too, matching Umbra's renderShadows: it is the first thing after the
    // shadow map, so its passes can read shadowtex/shadowcolor - Photon's prepare bakes the cloud shadow
    // map into colortex8 that terrain lighting then samples - and still land before any gbuffer geometry
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

    // called at the "translucent" profiler anchor - after all opaque world content (terrain, entities,
    // particles, weather), before the translucent block layer
    // snapshots the pre-translucent depth (depthtex1) and runs the pack's deferred chain, then points
    // world rendering at the post-deferred gbuffer
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
            // Re-establish the custom image/sampler bindings (Umbra binds images at every program use; the shadow
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

    // Called right before the solid hand draws: snapshot the pre-hand depth (depthtex2).
    public void beginHand() {
        if (!this.worldRenderingActive) {
            return;
        }
        copyDepthTexture(this.renderTargets.getDepthTextureNoHand());
    }

    // starts the first-person hand gbuffer pass
    // matching OptiFine's ShadersRender.renderHand0 (and Umbra's HandRenderer.renderSolid), the solid
    // hand draws into the *pre-deferred* gbuffer, before beginTranslucents() runs the pack's deferred chain
    // that is what lights the hand: deferred packs such as Complementary write only albedo, normal and
    // lightmap data in gbuffers_hand and do all shading in deferred*, so a hand drawn after that chain
    // stays as raw unlit gbuffer data
    // when the pack has no hand program, the hand still renders into colortex0 fixed-function
    // returns true when hand rendering should proceed and endHandRendering() must be called
    public boolean beginHandRendering() {
        return beginHandRendering(ProgramId.Hand, 16); // MC_RENDER_STAGE_HAND_SOLID
    }

    // starts OptiFine's late hand pass (renderHand1), after translucent world geometry has drawn but
    // before the composite/final chain consumes the gbuffer
    // this keeps nearby translucent collision panes from being blended over the first-person hand until
    // it appears to vanish
    public boolean beginHandTranslucentRendering() {
        return beginHandRendering(ProgramId.HandWater, 23); // MC_RENDER_STAGE_HAND_TRANSLUCENT
    }

    // Binds the hand program and depth setup; false when the pack has none
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
        resyncTextureUnitZero();
        resetVanillaVertexArrayState();

        GbufferPrograms.Entry entry = this.gbufferPrograms != null ? this.gbufferPrograms.get(programId) : null;
        // Keep the phase honest. This method binds a gbuffer program without going through setPhase, and the held
        // item draws through RenderItem underneath it, so refreshDynamicUniforms has to be able to find the hand
        // program to give that item its currentRenderedItemId — otherwise its GL_CURRENT_PROGRAM guard sees a
        // mismatch against the stale phase and skips the update, leaving the hand on whatever id the world left.
        this.currentPhase = programId;
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

    // hands the fixed-function vertex pipeline back to vanilla in a state it can actually use, before the
    // first-person arm draws
    // vanilla submits the arm through client arrays - glVertexPointer/glTexCoordPointer plus
    // glEnableClientState, see ForgeHooksClient.preDraw
    // on the compatibility profile those alias generic attribute slots - 0 is gl_Vertex, 2 gl_Normal,
    // 3 gl_Color, 8..15 gl_MultiTexCoord0..7 - the same aliasing FullscreenQuadRenderer relies on
    // deliberately
    // when a generic array and the aliased conventional array are both enabled for a slot, *the generic
    // array wins*, and every vertex then reads a single value: the attribute goes constant
    // that is the measured hand bug - gl_MultiTexCoord0 was constant across the whole draw, so the arm
    // and its second ("jacket") layer each sampled one texel: a flat green arm inside a flat shell, the
    // shell visible only because a constant UV lands on an opaque texel instead of the transparent
    // overlay it should have hit and been discarded for
    // position, normal and colour survived because nothing had left *their* slots enabled
    // leftover state here poisons the session, not just the frame: ModelRenderer bakes the arm into a
    // display list on first render and glDrawArrays dereferences the arrays at compile time, so one bad
    // compile is baked in for good
    // this runs before the first arm draw, so that compile happens clean too
    public static void resetVanillaVertexArrayState() {
        LWJGL.glBindVertexArray(0);
        LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        LWJGL.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        for (int slot = 0; slot < VANILLA_ALIASED_ATTRIBUTE_SLOTS; slot++) {
            LWJGL.glDisableVertexAttribArray(slot);
        }
    }

    // gl_Vertex/gl_Normal/gl_Color/gl_MultiTexCoord0..7 all alias generic slots below this.
    private static final int VANILLA_ALIASED_ATTRIBUTE_SLOTS = 16;

    // hands texture unit 0 back to vanilla in a state its caches can be trusted about, right before the
    // first-person hand draws
    // ItemRenderer.renderArmFirstPerson binds the player skin with
    // TextureManager.bindTexture(getLocationSkin()), which bottoms out in GlStateManager.bindTexture -
    // guarded by *both* a cached active-unit index and a cached per-unit texture name
    // the pipeline has to select scratch/sampler units with raw glActiveTexture + glBindTexture (shadow
    // mipmaps, depth copies, custom textures and images), which those caches never see
    // once the cache and GL disagree about unit 0, vanilla's skin bind silently no-ops and the arm
    // samples whatever unit 0 really holds - the block atlas
    // that is exactly the reported symptom: a flat lime arm inside an opaque orange box, because the
    // skin's second ("jacket") layer is fully transparent and would have been discarded, while atlas
    // texels are opaque and never are
    // bouncing through another unit defeats the active-unit cache, and clearing the binding defeats the
    // texture-name cache, so the very next bindTexture call is guaranteed to reach GL
    private static void resyncTextureUnitZero() {
        // resetToUnit0 already performs the step-through-unit-1 dance that used to be inlined on the two lines above
        // it, and the cached unbind below leaves real GL and the cache both holding 0 — so the trailing raw
        // glBindTexture was redundant too. One coherent pair is the whole job.
        GlTextureUnits.resetToUnit0();
        GlStateManager.bindTexture(0);
    }

    // Restores state after the hand
    public void endHandRendering() {
        LWJGL.glUseProgram(0);
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        CapturedRenderingState.INSTANCE.setRenderStage(0); // MC_RENDER_STAGE_NONE
    }

    // feeds the first-person hand its lightmap coordinate
    // BSL's gbuffers_hand derives all its brightness from lmCoord = gl_TextureMatrix[1] * gl_MultiTexCoord1
    // and never samples the lightmap texture, while Sodium/Embeddium can leave the fixed-function
    // lightmap coord stale
    // set both the legacy current texcoord and the hand shader bridge uniform to the player's combined
    // light, matching Umbra's getPackedLightCoords(player)
    // the vanilla lightmap texture matrix (scale 1/256, translate 8/256) expects the raw [0,240] block
    // and sky values getCombinedLight packs
    private void setupHandLightmap(int packedLight) {
        float blockLight = getBlockLightmapCoord(packedLight);
        float skyLight = getSkyLightmapCoord(packedLight);
        LWJGL.glMultiTexCoord2f(OpenGlHelper.lightmapTexUnit, blockLight, skyLight);
        setupLightmapTextureMatrix();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    // Light at the player's eye, for the hand's lightmap
    private static int getHandPackedLight() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            return FULL_BRIGHT_LIGHTMAP;
        }
        BlockPos eyePos = new BlockPos(mc.player.posX, mc.player.posY + mc.player.getEyeHeight(), mc.player.posZ);
        return mc.world.getCombinedLight(eyePos, 0);
    }

    // Block light half of packed light as a lightmap coordinate
    private static float getBlockLightmapCoord(int packedLight) {
        return packedLight & 0xFFFF;
    }

    // Sky light half
    private static float getSkyLightmapCoord(int packedLight) {
        return (packedLight >>> 16) & 0xFFFF;
    }

    // Vanilla's lightmap texture matrix, which packs expect on unit 1
    private static void setupLightmapTextureMatrix() {
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.matrixMode(GL_TEXTURE_MODE);
        GlStateManager.loadIdentity();
        GlStateManager.translate(LIGHTMAP_TEXTURE_OFFSET, LIGHTMAP_TEXTURE_OFFSET, LIGHTMAP_TEXTURE_OFFSET);
        GlStateManager.scale(LIGHTMAP_TEXTURE_SCALE, LIGHTMAP_TEXTURE_SCALE, LIGHTMAP_TEXTURE_SCALE);
        GlStateManager.matrixMode(GL_MODELVIEW_MODE);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    // renderWorld RETURN: run the composite chain and final pass, then hand a clean GL state back to vanilla.
    public void finishWorldRendering() {
        if (!this.worldRenderingActive) {
            return;
        }
        this.worldRenderingActive = false;
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

        // Umbra FinalPassRenderer.SwapPass: buffers the chain leaves odd-flipped carry this frame's data on their
        // ALT side — copy it back to MAIN so next frame's baked FBOs and sampler snapshots read fresh data. NB:
        // glCopyTexSubImage2D reads the GL_READ_BUFFER of the framebuffer bound to GL_FRAMEBUFFER (bind(), not
        // bindAsReadBuffer() — Umbra hit TAA breakage on many drivers with the read-framebuffer binding).
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

    // Whether the pipeline is between beginWorldRendering and its end
    public boolean isWorldRenderingActive() {
        return this.worldRenderingActive;
    }

    // rain.depth — true when the pack wants rain and snow to write depth.
    public boolean shouldWriteRainAndSnowToDepthBuffer() {
        return this.rainDepth;
    }

    // beacon.beam.depth — true when the pack wants the beacon beam in depthtex.
    public boolean shouldWriteBeaconBeamToDepthBuffer() {
        return this.beaconBeamDepth;
    }

    // frustum.culling = false — the pack needs off-screen geometry drawn (Umbra shouldDisableFrustumCulling).
    public boolean shouldDisableFrustumCulling() {
        return !this.frustumCulling;
    }

    // occlusion.culling = false — the pack needs occluded geometry drawn.
    public boolean shouldDisableOcclusionCulling() {
        return !this.occlusionCulling;
    }

    // skipAllRendering — suppress all world geometry; the composite chain still runs.
    public boolean skipAllRendering() {
        return this.skipAllRendering;
    }

    // separateEntityDraws — entities are drawn in their own pass after the deferred chain.
    public boolean shouldSeparateEntityDraws() {
        return this.separateEntityDraws;
    }

    // particles.ordering — where particles fall relative to the deferred chain.
    public String getParticleOrdering() {
        return this.particleOrdering;
    }

    // backFace.<layer> - false when the pack wants that terrain layer's back faces drawn
    // layerOrdinal is BlockRenderLayer#ordinal()
    public boolean shouldCullBackFaces(int layerOrdinal) {
        return layerOrdinal < 0 || layerOrdinal >= this.backFaceCulling.length
                || this.backFaceCulling[layerOrdinal];
    }

    // Packs with a shadow pass replace vanilla's blob shadows
    public boolean shouldDisableVanillaEntityShadows() {
        return this.shadowRenderer != null;
    }

    // compiles the pack's shadowcomp compute passes (.csh, an Umbra extension - Complementary's floodfill
    // light propagation)
    // each family index becomes a compute-only pass carrying the flip snapshot of its place in the chain,
    // so its colortexN reads and colorimgN writes hit the same sides the rest of the frame does
    private void buildComputePasses(ShaderPack pack, BufferFlipper flipper) {
        for (int i = 0; i < ProgramArrayId.ShadowComposite.getNumPrograms(); i++) {
            Optional<ProgramSource> source = pack.getProgramSet().get(ProgramArrayId.ShadowComposite, i);
            if (!source.isPresent()) {
                continue;
            }
            List<ComputePass> computes = buildFamilyComputePasses(pack, source.get(), TextureStage.SHADOWCOMP);
            // A shadowcomp entry with a vertex+fragment pair draws a full-screen quad into shadowcolor0/1
            // (Umbra ShadowCompositeRenderer). None of the packs in circulation ship one — every shadowcomp in the
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

    // builds a raster shadowcomp pass: a full-screen quad into the shadow pass's own colour attachments,
    // at shadow-map resolution
    // unlike the numbered colour families these targets do not ping-pong, so the pass carries no flip state
    private FullscreenPass buildShadowCompositePass(ShaderPack pack, ProgramSource source, List<ComputePass> computes) {
        if (this.shadowRenderer == null) {
            LOGGER.warn("[Umbra] '{}' draws into shadowcolor but the pack declares no shadow program; skipping",
                    source.getName());
            return null;
        }
        String name = source.getName();
        try {
            UmbraProgram program = cachedProgram(source);
            if (program == null) {
                return null;
            }
            int[] drawBuffers = sanitizeShadowCompositeDrawBuffers(name, program.getDrawBuffers());
            UmbraFramebuffer framebuffer = new UmbraFramebuffer();
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
            return pass;
        } catch (Exception e) {
            LOGGER.error("[Umbra] Failed to build shadowcomp pass '{}'; it will be skipped: {}", name, e.getMessage());
            return null;
        }
    }

    // Only shadowcolor0/1 exist, so any higher index a shadowcomp DRAWBUFFERS names has nothing to attach to.
    private static int[] sanitizeShadowCompositeDrawBuffers(String name, int[] drawBuffers) {
        int[] sanitized = new int[drawBuffers.length];
        int count = 0;
        for (int buffer : drawBuffers) {
            if (buffer > 1) {
                LOGGER.warn("[Umbra] '{}' writes shadowcolor{}, but only shadowcolor0/1 exist; dropping it",
                        name, buffer);
                continue;
            }
            sanitized[count++] = buffer;
        }
        return count == 0 ? new int[]{0} : Arrays.copyOf(sanitized, count);
    }

    // compiles every compute stage attached to one program - the unsuffixed <name>.csh plus the
    // letter-suffixed <name>_a.csh .. <name>_z.csh Umbra extension
    // dispatch size follows Umbra's priority order: the indirect directive, then const ivec3 workGroups,
    // then const vec2 workGroupsRender, then one invocation per pixel of the render target
    private List<ComputePass> buildFamilyComputePasses(ShaderPack pack, ProgramSource source, TextureStage stage) {
        String[] computeSources = source.getComputeSources();
        if (computeSources.length == 0) {
            return java.util.Collections.emptyList();
        }
        if (!isProgramEnabled(pack, source.getName())) {
            return java.util.Collections.emptyList();
        }

        // The voxel-volume dispatch fallback is a shadowcomp-only workaround (Complementary's floodfill under-declares
        // its groups). Umbra never second-guesses a declared `workGroups`, and doing so elsewhere is actively wrong:
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
                String csh = com.bdmajora.impetus.umbra.gl.shader.ShaderMacros.injectDefines(
                        CustomTextureTransformer.transform(name, computeSources[variant], stage),
                        this.shaderDefines);
                csh = stabilizeShaderSource(name, csh);
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
                        LOGGER.warn("[Umbra] Compute pass '{}' requests indirect dispatch from undeclared bufferObject.{}",
                                name, indirectPointer[0]);
                    }
                }

                int[] workGroups = parseWorkGroups(csh, this.shaderDefines);
                if (workGroups != null && fallbackWorkGroups != null
                        && !coversVolume(workGroups, localSize, volume)) {
                    LOGGER.warn("[Umbra] Compute pass '{}' declared dispatch {}x{}x{} does not cover custom image volume {}x{}x{} with local {}x{}x{}; using {}x{}x{}",
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
                        // Umbra ComputeProgram.getWorkGroups' last resort: cover the screen, one invocation per pixel.
                        renderScale = new float[]{1.0f, 1.0f};
                        workGroups = new int[]{1, 1, 1};
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
                com.bdmajora.impetus.umbra.uniforms.custom.ActiveCustomUniforms.assignTo(uniforms);
                built.add(new ComputePass(name, program, uniforms.buildUniforms(),
                        workGroups[0], workGroups[1], workGroups[2],
                        renderScale != null ? renderScale[0] : Float.NaN,
                        renderScale != null ? renderScale[1] : Float.NaN,
                        localSize != null ? localSize[0] : 1,
                        localSize != null ? localSize[1] : 1,
                        indirectBuffer, indirectOffset));
            } catch (Exception e) {
                LOGGER.error("[Umbra] Failed to build compute pass '{}'; it will be skipped: {}", name, e.getMessage());
            }
        }
        return built;
    }

    // Honours program.<name>.enabled
    private static boolean isProgramEnabled(ShaderPack pack, String programName) {
        return pack.getProperties().getProgramEnabled(programName).orElse(Boolean.TRUE);
    }

    private static final Pattern UNINITIALIZED_LIGHT_VOLUME =
            Pattern.compile("(?m)^([\\t ]*)vec4\\s+lightVolume\\s*;[\\t ]*$");
    // source stabilizer for a hazard proven by the shader pins: Complementary's GetComplexLightVolume can
    // accumulate into an uninitialized vec4, and zero-init is required under our transformed sources
    // a companion rewrite used to flatten the pack's
    // fract(d + goldenRatio * mod(float(frameCounter), 3600.0)) dither reroll into a no-op fract(d),
    // dating from the era when block-edge shimmer was being chased
    // that root cause turned out to be the zeroed at_midBlock attribute, and the rewrite outlived it -
    // while silently breaking every raymarch that depends on the reroll
    // Complementary's light shafts take only ~15 samples over the whole ray and rely on a per-frame
    // dither offset for TAA to converge them; frozen, the sample planes become static screen-space slabs
    // of lit fog that cut straight across terrain
    // Umbra never rewrites pack source this way, and SystemTimeUniforms advances frameCounter per frame
    // exactly as Umbra does, so the pack's own reroll is left to run as authored
    // #version <number> — the first one wins, matching the driver preprocessor.
    private static final Pattern VERSION_DIRECTIVE = Pattern.compile("(?m)^\\s*#version\\s+(\\d+)");

    // the ARB_shader_texture_lod entry points
    // in a #version 130+ shader these are only legal when the pack enabled the extension itself, and the
    // core textureGrad family replaces them - the rename Umbra does in CommonTransformer
    // Just Colored Lighting and Sildur's call texture2DGradARB from #version 430 compatibility sources,
    // which the NVIDIA driver rejects outright (error C7531)
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

    // rewrites the *ARB texture-lookup entry points to their core equivalents, but only for sources that
    // declare #version 130 or newer
    // a GLSL 120 pack that calls them has to enable the extension itself and textureGrad would not exist
    // there, so those are left exactly as authored
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
        }
        return result;
    }

    // resolves the #if directives the driver's preprocessor cannot legally accept - float comparisons,
    // and expressions that are outright malformed - leaving everything else for the driver
    // split out of stabilizeShaderSource because the two legacy paths need exactly this and none of the
    // rest: both hand a #version 120 source to a 330 rewrite, where normalizeArbTextureLookups is a
    // no-op by construction
    // every caller must inline the macro environment as #define lines first, which is what makes the
    // fold self-contained
    // there are three callers, each of which reaches the driver by a different route and each of which
    // had to be fixed separately: stabilizeShaderSource (gbuffer programs, via ShaderProgramCompiler),
    // the legacy fullscreen composite/deferred/final path in this class, and UmbraTerrainProgramOverride
    // (terrain and, critically, the translucent water pass)
    public static String foldUncompilableConditionals(String name, String source) {
        return GlslPreprocessor.foldFloatConditionals(source, java.util.Collections.emptyMap());
    }

    // Applies the fixes every source needs before any transform
    public static String stabilizeShaderSource(String name, String source) {
        source = foldUncompilableConditionals(name, source);
        source = normalizeArbTextureLookups(name, source);
        source = com.bdmajora.impetus.umbra.terrain.GlslIntegerOverloadPolyfill.widenIntegerBuiltinCalls(name, source);
        Matcher declaration = UNINITIALIZED_LIGHT_VOLUME.matcher(source);
        if (declaration.find()) {
            source = declaration.replaceAll("$1vec4 lightVolume = vec4(0.0);");
        }
        return source;
    }

    // Compute work group counts, honouring #ifdef gates
    private static int[] parseWorkGroups(String source, Map<String, String> defines) {
        String active = preprocessActiveShaderSource(source, defines);
        int[] workGroups = parseWorkGroupsDirect(active);
        return workGroups != null ? workGroups : parseWorkGroupsDirect(source);
    }

    // the `const vec2 workGroupsRender` scale factors, or null when not declared
    private static float[] parseWorkGroupsRender(String source, Map<String, String> defines) {
        String active = preprocessActiveShaderSource(source, defines);
        float[] scale = parseWorkGroupsRenderDirect(active);
        return scale != null ? scale : parseWorkGroupsRenderDirect(source);
    }

    // workGroupsRender directive, screen-relative
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

    // Parses every indirect.<pass> = <bufferObjectIndex> <offsetBytes> directive (Umbra syntax).
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
                LOGGER.warn("[Umbra] Malformed indirect directive '{} = {}'", key, value);
            }
        });
        return pointers;
    }

    // delegates to GlslPreprocessor#resolveConditionals, which additionally falls back to the raw source
    // when resolution yields nothing - an unterminated #if otherwise swallows the rest of the file and
    // the scan sees no directives at all
    // kept as a named seam because the compute-directive callers pass their own define maps
    private static String preprocessActiveShaderSource(String source, Map<String, String> defines) {
        return GlslPreprocessor.resolveConditionals(source, defines);
    }

    // workGroups directive, absolute
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

    // Whether the dispatch reaches every element of the target volume
    private static boolean coversVolume(int[] workGroups, int[] localSize, int[] volume) {
        return workGroups[0] * localSize[0] >= volume[0]
                && workGroups[1] * localSize[1] >= volume[1]
                && workGroups[2] * localSize[2] >= volume[2];
    }

    // layout(local_size_x = ...) values
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

    // One axis of a workGroups directive
    private static int parseNamedWorkGroup(String source, String axis) {
        Matcher matcher = Pattern.compile("const\\s+int\\s+workGroups" + axis + "\\s*=\\s*(\\d+)\\s*;")
                .matcher(source);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    // Null for anything not a positive integer
    private static Integer parsePositiveInt(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Integer ceiling division
    private static int ceilDiv(int value, int divisor) {
        return Math.max(1, (value + divisor - 1) / divisor);
    }

    // Removes line and block comments before directive parsing
    private static String stripGlslComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    // runs the shadowcomp compute chain: a full barrier makes the shadow pass's imageStore voxelization
    // visible, each pass dispatches, and a closing barrier publishes the results to every later sampler read
    private void dispatchComputePasses() {
        if (this.shadowCompPasses.isEmpty()) {
            return;
        }
        // Umbra rebinds all images + paired samplers at every compute use (ComputeProgram.use -> images.update()). The
        // Impetus shadow-terrain draw that just voxelized runs through managed code that can reset texture/image
        // units, so re-establish the voxel/floodfill bindings here rather than trusting the frame-start bindAll to
        // survive it — otherwise the compute could read/write the wrong (or unbound) volume.
        bindShaderPackResources();
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

    // dispatches a list of compute programs in order, publishing each one's writes with a full barrier
    // before the next runs - Complementary's floodfill iterations read the previous one's output, and a
    // family pass's fragment stage reads its computes' output
    private void dispatchComputes(List<ComputePass> computes) {
        for (ComputePass pass : computes) {
            pass.program.bind();
            bindShaderPackResources();
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

    // Frees the compute programs attached to a pass family
    private static void destroyFamilyComputes(List<FullscreenPass> family) {
        for (FullscreenPass pass : family) {
            for (ComputePass compute : pass.computes) {
                compute.program.destroy();
            }
        }
    }

    // Captures the block-atlas dimensions for the atlasSize/terrainTextureSize uniforms.
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

    // runs a numbered family that executes in the middle of world rendering (begin, prepare): the quads
    // draw with depth, blend and alpha test off, then the gbuffer state the world expects is put back
    // the composite and deferred chains do this inline because they also switch flip snapshots and
    // gbuffer framebuffers
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

    // Runs one full-screen pass into its framebuffer (or Minecraft's framebuffer for the final pass).
    private void runPass(FullscreenPass pass, Minecraft mc) {
        // Umbra CompositeRenderer.renderAll: the pass's computes dispatch first, under this pass's flip state, then a
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
            int passWidth = pass.viewportWidth > 0 ? pass.viewportWidth : this.renderTargets.getWidth();
            int passHeight = pass.viewportHeight > 0 ? pass.viewportHeight : this.renderTargets.getHeight();
            // `scale.<program>` on top of that, arithmetic per Umbra CompositeRenderer:317-321 — the offsets are
            // fractions of the pass size, not texels, and the scaled extent is truncated rather than rounded.
            LWJGL.glViewport(
                    (int) (passWidth * pass.viewportOffsetX),
                    (int) (passHeight * pass.viewportOffsetY),
                    (int) (passWidth * pass.viewportScale),
                    (int) (passHeight * pass.viewportScale));
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
        if (this.modernPack) {
            // The [0,1] fullscreen quad maps to NDC via an ortho projection (modelview/texture stay identity), so
            // ftransform() = projection*modelview*gl_Vertex = ortho*[0,1] = NDC and (gl_TextureMatrix[0]*
            // gl_MultiTexCoord0) passes the [0,1] texcoords through. Save/restore so the hand and GUI that vanilla
            // draws after the composite chain are unaffected.
            pushFullscreenFixedFunctionMatrices();
            this.quadRenderer.draw();
            popFixedFunctionMatrices();
        } else {
            this.quadRenderer.draw();
        }
    }

    // Generates mips on targets the pass declared via mipmapEnabled
    private void setupMipmappedBuffers(FullscreenPass pass) {
        if (pass.mipmappedBuffers.nextSetBit(0) < 0) {
            return;
        }
        GlTextureUnits.selectScratch(MIPMAP_SCRATCH_UNIT);
        for (int index = pass.mipmappedBuffers.nextSetBit(0); index >= 0;
             index = pass.mipmappedBuffers.nextSetBit(index + 1)) {
            UmbraRenderTarget target = this.renderTargets.get(index);
            if (target != null) {
                target.generateMipmaps(pass.flipsBefore.get(index));
            }
        }
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GlTextureUnits.resetToUnit0();
    }

    // Returns every target to the non-mipmapped filter at frame end
    private void resetRenderTargetMipmaps() {
        GlTextureUnits.selectScratch(MIPMAP_SCRATCH_UNIT);
        for (int i = 0; i < UmbraRenderTargets.MAX_COLOR_BUFFERS; i++) {
            UmbraRenderTarget target = this.renderTargets.get(i);
            if (target != null) {
                target.resetMipmaps();
            }
        }
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GlTextureUnits.resetToUnit0();
    }

    // Clears any pending GL error so the next check is attributable
    public static void drainGlError() {
        while (LWJGL.glGetError() != 0) {
            // discard
        }
    }

    // Logs a pending GL error with the call site
    public static void reportGlError(String where) {
        int error = LWJGL.glGetError();
        if (error != 0) {
            LOGGER.warn("[Umbra] GL error 0x{} ({}) at: {}",
                    Integer.toHexString(error), error, where);
        }
    }

    // Fixed-function matrix modes (GL_MODELVIEW/PROJECTION/TEXTURE); not in the core GL wrapper we use elsewhere.
    private static final int GL_MODELVIEW_MODE = 0x1700;
    private static final int GL_PROJECTION_MODE = 0x1701;
    private static final int GL_TEXTURE_MODE = 0x1702;

    // Identity matrices for fullscreen passes that still use fixed-function transforms
    private static void pushFullscreenFixedFunctionMatrices() {
        // Projection is the ortho that maps the [0,1] fullscreen quad to NDC [-1,1] (matching Umbra's composite
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

    // Restores the matrices
    private static void popFixedFunctionMatrices() {
        GlStateManager.matrixMode(GL_PROJECTION_MODE);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL_TEXTURE_MODE);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL_MODELVIEW_MODE);
        GlStateManager.popMatrix();
    }

    // copies the depth of the active gbuffer framebuffer into destination - how OptiFine snapshots
    // depthtex1/depthtex2
    // runs on a scratch texture unit so no vanilla-tracked binding is disturbed
    private void copyDepthTexture(DepthTexture destination) {
        // Umbra/OptiFine copy from the shader framebuffer's depth attachment. Do not rely on whatever framebuffer a
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

    // Binds and returns the previous binding for restore
    private static int bindScratchTexture2D(int texture) {
        GlTextureUnits.selectScratch(DEPTH_COPY_SCRATCH_UNIT);
        int previousTexture = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        return previousTexture;
    }

    // Rebinds the saved texture
    private static void restoreScratchTexture2D(int texture) {
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GlTextureUnits.resetToUnit0();
    }

    // Back to vanilla's framebuffer
    private static void bindMainRenderTarget(Minecraft mc) {
        if (OpenGlHelper.isFramebufferEnabled()) {
            mc.getFramebuffer().bindFramebuffer(true);
        } else {
            LWJGL.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            LWJGL.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
        }
    }

    // Resets draw and read buffers after an FBO with custom ones
    private static void restoreMainDrawReadBuffers(Minecraft mc) {
        if (OpenGlHelper.isFramebufferEnabled()) {
            LWJGL.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
            LWJGL.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        } else {
            LWJGL.glDrawBuffers(GL_BACK_BUFFER);
            LWJGL.glReadBuffer(GL_BACK_BUFFER);
        }
    }

    // Binds each colortex the pass samples, respecting flips
    private void bindColorSamplers(FullscreenPass pass) {
        for (int i = UmbraRenderTargets.MAX_COLOR_BUFFERS - 1; i >= 0; i--) {
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

    // binds the colorimgN images for one pass
    // Umbra's binding is flip-aware, so a compute writing colorimg4 hits the exact texture the following
    // programs sample as colortex4
    private void bindRenderTargetImages(FullscreenPass pass) {
        if (this.renderTargetImageUnits.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, Integer> entry : this.renderTargetImageUnits.entrySet()) {
            UmbraRenderTarget target = this.renderTargets.get(entry.getKey());
            if (target == null) {
                continue;
            }
            int texture = pass.flipsBefore.get(entry.getKey()) ? target.getAltTexture() : target.getMainTexture();
            LWJGL.glBindImageTexture(entry.getValue(), texture, 0, false, 0, GL15.GL_READ_WRITE,
                    target.getInternalFormat().getInternalFormat());
        }
        bindShadowColorImages();
    }

    // binds shadowcolorimg0/1 over the shadow pass's colour attachments
    // they do not ping-pong, so unlike the render-target images there is no flip state to follow
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
                    UmbraShadowRenderer.SHADOW_COLOR_INTERNAL_FORMAT);
        }
    }

    // depthtex0, 1 and 2
    private void bindDepthSamplers() {
        // Bind both the high fullscreen units and the OptiFine 1.12 gbuffers units (6/12).
        bindDepthSampler(DEPTH_TEX_0_UNIT, this.renderTargets.getDepthTexture());
        bindDepthSampler(DEPTH_TEX_1_UNIT, this.renderTargets.getDepthTextureNoTranslucents());
        bindDepthSampler(DEPTH_TEX_2_UNIT, this.renderTargets.getDepthTextureNoHand());
        bindDepthSampler(GBUFFER_DEPTH_TEX_0_UNIT, this.renderTargets.getDepthTexture());
        bindDepthSampler(GBUFFER_DEPTH_TEX_1_UNIT, this.renderTargets.getDepthTextureNoTranslucents());
        GlTextureUnits.resetToUnit0();
    }

    // normals and specular atlases, or the neutral fallbacks
    private void bindGbufferPbrSamplers() {
        // PBR maps on the gbuffer-stage normals/specular units (2/3, through GlStateManager so its cache stays
        // coherent). Fullscreen passes overwrite these units with colortex2/3; rebind before later gbuffers stages
        // such as water and hand sample the atlas again.
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + 2);
        GlStateManager.bindTexture(com.bdmajora.impetus.umbra.pbr.PBRAtlasManager.getNormalsAtlas(
                this.defaultNormals.getTextureId()));
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + 3);
        GlStateManager.bindTexture(com.bdmajora.impetus.umbra.pbr.PBRAtlasManager.getSpecularAtlas(
                this.defaultSpecular.getTextureId()));
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    // One depth texture to one unit
    private static void bindDepthSampler(int unit, DepthTexture texture) {
        LWJGL.glBindSampler(unit, 0);
        bindTextureUnit(unit, texture.getTextureId());
    }

    // iris_overlay is constant for the whole frame; bound alongside the other frame-long samplers.
    private void bindOverlayTexture() {
        bindTextureUnit(OVERLAY_TEX_UNIT, this.noOverlayTexture.getTextureId());
        bindTextureUnit(GBUFFER_OVERLAY_UNIT, this.noOverlayTexture.getTextureId());
        GlTextureUnits.resetToUnit0();
    }

    // noisetex
    private void bindNoiseTexture() {
        int customNoise = this.customTextureManager.getNoiseTextureId();
        int texture = customNoise != -1 ? customNoise : this.noiseTexture.getTextureId();
        bindTextureUnit(NOISE_TEX_UNIT, texture);
        bindTextureUnit(GBUFFER_NOISE_TEX_UNIT, texture);
        GlTextureUnits.resetToUnit0();
    }

    // shadowtex and shadowcolor, or the stub when there is no shadow pass
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
        // expects them to read raw depth, with comparison moved to the *HW aliases (Umbra UmbraSamplers:151).
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

    // One shadow depth texture with its compare sampler object
    private void bindShadowDepthUnit(int unit, int texture, int sampler) {
        LWJGL.glBindSampler(unit, sampler);
        bindTextureUnit(unit, texture);
    }

    // binds one sampler unit, leaving the selector *on that unit* - callers batch several of these and
    // reset once via GlTextureUnits#resetToUnit0() rather than paying a reset per bind
    // every caller does, and that is load-bearing for units at or above GlTextureUnits#CACHED_UNITS,
    // which take the raw branch
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

    // the comparison sampler for a shadow depth texture
    // bound only to the *HW units, which only programs that declared a sampler2DShadow are pointed at -
    // and such a program is undefined without depth comparison
    // so comparison is unconditional here; shadowHardwareFiltering only selects the filtering flavour,
    // exactly as it does for the raw units through the texture's own parameters
    private int shadowHardwareSamplerFor(int index) {
        if (this.shadowMipmap[index]) {
            return this.shadowNearest[index] ? this.shadowMippedNearestHwSampler : this.shadowMippedLinearHwSampler;
        }
        return this.shadowNearest[index] ? this.shadowNearestHwSampler : this.shadowLinearHwSampler;
    }

    // binds the readable colortex4..7 textures to their sampler units for the gbuffer/world phase
    // on the 1.12 OptiFine path colortex4..7 (gaux1..4) live on aux units 7..10; fullscreen programs use
    // their own table
    // gbuffer programs sample these - most importantly MakeUp and other packs read gaux4 (= colortex7)
    // in gbuffers_terrain as the atmosphere/fog colour that distant terrain fades toward
    // without this bind, unit 7 held a stale/garbage texture, so the fog blended distant terrain toward
    // a huge value (clamped to the shader's 50.0 ceiling) - the blown-out horizon band, which also
    // dragged auto-exposure down
    // units 0..3 are deliberately left alone: unit 0 is the block atlas, unit 1 the lightmap, and units
    // 2/3 the PBR normals/specular maps (see bindGbufferPbrSamplers)
    // custom-texture overrides - gaux2 pointed at a pack noise texture, say - point their sampler
    // uniforms at their own high units and are unaffected
    // called once at gbuffer start and re-asserted on every fixed-function phase switch, since
    // vanilla/Sodium may disturb these units mid-frame
    private void bindGbufferColorSamplers() {
        bindGbufferColorSamplers(this.activeGbufferSamplerFlips);
    }

    // colortex samplers for a gbuffer program, using the feedback copies where needed
    private void bindGbufferColorSamplers(BitSet samplerFlips) {
        for (int i = 4; i < UmbraRenderTargets.MAX_COLOR_BUFFERS; i++) {
            if (this.renderTargets.get(i) == null) {
                continue;
            }
            UmbraRenderTarget target = this.renderTargets.get(i);
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

    // Copies targets a gbuffer program both reads and writes, since that is undefined otherwise
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

    // Targets packs commonly read back during the gbuffer stage
    private static boolean isGbufferFeedbackSampler(int logicalIndex) {
        return logicalIndex >= 0
                && logicalIndex < GBUFFER_COLOR_TEXTURE_UNITS.length
                && GBUFFER_COLOR_TEXTURE_UNITS[logicalIndex] >= 0;
    }

    // Blit so the program reads a stable copy
    private void copyGbufferFrontToBack(int logicalIndex) {
        if (this.gbufferFeedbackCopyFramebuffer == null) {
            this.gbufferFeedbackCopyFramebuffer = new UmbraFramebuffer();
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

    // Returns to unit 0 with the block atlas, as vanilla expects
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
        for (int i = UmbraRenderTargets.MAX_COLOR_BUFFERS - 1; i >= 8; i--) {
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

    // Custom textures and images the pack declared
    private void bindShaderPackResources() {
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
            this.customImageManager.bindAll();
        }
    }

    // Image bindings for the current program
    public void bindCustomImages() {
        bindShaderPackResources();
    }

    // ------------------------------------------------------------------ teardown

    // Frees all GL resources. Must run on the render thread. Safe to call more than once.
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
        // Umbra sweeps the same registry on teardown: a holder replaced without destroy() would otherwise strand
        // hundreds of megabytes of video memory per reload with nothing left referencing it.
        com.bdmajora.impetus.umbra.gl.buffer.ShaderStorageBufferHolder.forceDeleteBuffers();
        com.bdmajora.impetus.umbra.uniforms.custom.ActiveCustomUniforms.clear();
        com.bdmajora.impetus.umbra.terrain.UmbraTerrainProgramOverride.destroyShadowPrograms();
        if (this.shadowRenderer != null) {
            this.shadowRenderer.destroy();
        }
        if (this.gbufferPrograms != null) {
            this.gbufferPrograms.destroy();
        }
        activeGbufferSamplerUnits = GBUFFER_SAMPLER_UNITS;
        activeGbufferSamplerOverrides = java.util.Collections.emptyMap();
        com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setBlockStateIds(null);
        com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setBlockRenderLayers(null);
        com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setItemIds(null);
        com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setEntityIds(null);
        com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setVoxelRenderDistanceChunks(0);
        com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setDynamicHandLight(true);
        com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setSeparateAo(false);
        com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setOldLighting(false);
        com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setOldHandLight(true);
        com.bdmajora.impetus.umbra.material.WorldRenderingSettings.setVoxelizeLightBlocks(false);
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
        for (UmbraProgram program : this.compiledPrograms.values()) {
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
