package org.taumc.celeritas.iris.pipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.taumc.celeritas.iris.gl.framebuffer.IrisFramebuffer;
import org.taumc.celeritas.iris.gl.program.DrawBuffers;
import org.taumc.celeritas.iris.gl.blending.ProgramBlendState;
import org.taumc.celeritas.iris.gl.program.GlProgram;
import org.taumc.celeritas.iris.gl.program.IrisProgram;
import org.taumc.celeritas.iris.gl.program.ProgramBuilder;
import org.taumc.celeritas.iris.gl.program.ProgramUniforms;
import org.taumc.celeritas.iris.gl.shader.GlShader;
import org.taumc.celeritas.iris.gl.shader.ShaderType;
import org.taumc.celeritas.iris.gl.texture.InternalTextureFormat;
import org.taumc.celeritas.iris.gl.texture.PlainTexture;
import org.taumc.celeritas.iris.gl.texture.StubShadowMap;
import org.taumc.celeritas.iris.shaderpack.ProgramSource;
import org.taumc.celeritas.iris.shaderpack.ShaderPack;
import org.taumc.celeritas.iris.shaderpack.loading.ProgramArrayId;
import org.taumc.celeritas.iris.shaderpack.loading.ProgramId;
import org.taumc.celeritas.iris.shaderpack.texture.TextureStage;
import org.taumc.celeritas.iris.targets.BufferFlipper;
import org.taumc.celeritas.iris.targets.DepthTexture;
import org.taumc.celeritas.iris.targets.IrisRenderTarget;
import org.taumc.celeritas.iris.targets.IrisRenderTargets;
import org.taumc.celeritas.iris.targets.NoiseTexture;
import org.taumc.celeritas.iris.terrain.FullscreenTransformer;
import org.taumc.celeritas.iris.terrain.ModernPackTransformer;
import org.taumc.celeritas.iris.uniforms.CapturedRenderingState;
import org.taumc.celeritas.iris.uniforms.CelestialUniforms;
import org.taumc.celeritas.iris.uniforms.CommonUniforms;
import org.taumc.celeritas.iris.uniforms.EyeBrightnessTracker;
import org.taumc.celeritas.iris.uniforms.MatrixUniforms;
import org.taumc.celeritas.iris.uniforms.SystemTimeUniforms;
import org.taumc.celeritas.lwjgl.GL11;
import org.taumc.celeritas.lwjgl.GL13;
import org.taumc.celeritas.lwjgl.GL30;
import org.taumc.celeritas.mixin.core.terrain.ActiveRenderInfoAccessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The Iris-native frame pipeline: owns the gbuffer framebuffer that world rendering is redirected into, and the
 * composite/final full-screen chain that turns the gbuffer into the image on screen.
 * <p>
 * Frame flow (driven by the {@code EntityRenderer} mixin):
 * <ol>
 * <li>{@link #beginWorldRendering} (renderWorld HEAD) — binds the gbuffer FBO, so vanilla's own clear and all world
 * rendering (Embeddium terrain via the pack's transformed {@code gbuffers_terrain}, everything else fixed-function)
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
    private static final Logger LOGGER = LogManager.getLogger("Celeritas/Iris");

    // Texture units: colortex0..15 occupy units 0..15 (Iris parity); the rest live on units 16+, clear of anything
    // vanilla touches. NVIDIA exposes 32 fragment texture-image units, so there is ample room.
    private static final int DEPTH_TEX_0_UNIT = 16;
    private static final int DEPTH_TEX_1_UNIT = 17;
    private static final int DEPTH_TEX_2_UNIT = 18;
    private static final int SHADOW_TEX_0_UNIT = 19;
    private static final int SHADOW_TEX_1_UNIT = 20;
    private static final int SHADOW_COLOR_0_UNIT = 21;
    private static final int SHADOW_COLOR_1_UNIT = 22;
    private static final int NOISE_TEX_UNIT = 23;
    /** Highest logical colortex index shader-pack gbuffer stages may address. FBO attachment points are packed. */
    private static final int GBUFFER_ATTACHMENT_LIMIT = IrisRenderTargets.MAX_COLOR_BUFFERS;
    /** High texture unit used transiently for depth-copy binds so no vanilla-tracked unit is disturbed. */
    private static final int DEPTH_COPY_SCRATCH_UNIT = 24;
    /**
     * Dedicated units for the pack's custom textures and image samplers, above every reserved sampler. Unit 24 is
     * shared only by transient depth-copy/capture helpers; custom textures are rebound after those scratch uses.
     */
    private static final int CUSTOM_TEX_FIRST_UNIT = DEPTH_COPY_SCRATCH_UNIT;
    private static final int GL_MAX_TEXTURE_IMAGE_UNITS = 0x8872;
    private static final int GL_BACK_BUFFER = 0x0405;
    private static final int SHADER_PACK_RESOURCE_BARRIERS = 0x00000020 | 0x00000008 | 0x00002000;
    /** Draw-buffer mask for fixed-function content with no pack program: plain color into colortex0 only. */
    private static final int[] FIXED_FUNCTION_MASK = {0};

    /** Sampler name → texture unit, covering both the modern names and the OptiFine legacy aliases. */
    private static final Map<String, Integer> SAMPLER_UNITS = new LinkedHashMap<>();

    static {
        // Legacy OptiFine aliases only exist for the first 8 targets (colortex0..7); colortex8..15 have no alias.
        String[] legacyColor = {"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"};
        for (int i = 0; i < IrisRenderTargets.MAX_COLOR_BUFFERS; i++) {
            SAMPLER_UNITS.put("colortex" + i, i);
            if (i < legacyColor.length) {
                SAMPLER_UNITS.put(legacyColor[i], i);
            }
        }
        SAMPLER_UNITS.put("depthtex0", DEPTH_TEX_0_UNIT);
        SAMPLER_UNITS.put("gdepthtex", DEPTH_TEX_0_UNIT);
        SAMPLER_UNITS.put("depthtex1", DEPTH_TEX_1_UNIT);
        SAMPLER_UNITS.put("depthtex2", DEPTH_TEX_2_UNIT);
        // Shadow samplers are parked on unused units so a shadow-reading pack samples nothing instead of colortex0
        // (sampler uniforms default to unit 0). The shadow pass itself is a later phase.
        SAMPLER_UNITS.put("shadowcolor1", SHADOW_COLOR_1_UNIT);
        SAMPLER_UNITS.put("shadowcolor0", SHADOW_COLOR_0_UNIT);
        SAMPLER_UNITS.put("shadowcolor", SHADOW_COLOR_0_UNIT);
        SAMPLER_UNITS.put("shadowtex0", SHADOW_TEX_0_UNIT);
        SAMPLER_UNITS.put("shadow", SHADOW_TEX_0_UNIT);
        SAMPLER_UNITS.put("watershadow", SHADOW_TEX_0_UNIT);
        SAMPLER_UNITS.put("shadowtex1", SHADOW_TEX_1_UNIT);
        SAMPLER_UNITS.put("noisetex", NOISE_TEX_UNIT);
    }

    /** One full-screen pass: a composite ({@code framebuffer != null}) or the final pass (drawn to the screen). */
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

        FullscreenPass(String name, IrisProgram program, ProgramUniforms uniforms, IrisFramebuffer framebuffer,
                       int[] colorSamplers, int[] drawBuffers, ProgramBlendState blendState) {
            this.name = name;
            this.program = program;
            this.uniforms = uniforms;
            this.framebuffer = framebuffer;
            this.colorSamplers = colorSamplers;
            this.drawBuffers = drawBuffers;
            this.blendState = blendState;
        }
    }

    private final IrisRenderTargets renderTargets;
    private final FullscreenQuadRenderer quadRenderer;
    private final NoiseTexture noiseTexture;
    /** Fallback PBR inputs for the gbuffer stage: flat up-normal and black specular (OptiFine's defaults). */
    private final PlainTexture defaultNormals;
    private final PlainTexture defaultSpecular;
    /** "Always lit" 1×1 shadow map on the shadowtex units until the real shadow pass exists. */
    private final StubShadowMap stubShadowMap;
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
    /** Compute passes (shadowcomp {@code .csh}), dispatched right after the shadow map renders. */
    private final List<ComputePass> computePasses = new ArrayList<>();

    /** One compute dispatch: the linked program, its uniforms, and the work-group counts. */
    private static final class ComputePass {
        final String name;
        final GlProgram program;
        final ProgramUniforms uniforms;
        final int groupsX;
        final int groupsY;
        final int groupsZ;

        ComputePass(String name, GlProgram program, ProgramUniforms uniforms, int groupsX, int groupsY, int groupsZ) {
            this.name = name;
            this.program = program;
            this.uniforms = uniforms;
            this.groupsX = groupsX;
            this.groupsY = groupsY;
            this.groupsZ = groupsZ;
        }
    }
    /**
     * The gbuffers/shadow-stage sampler overrides of the <em>active</em> pipeline, consulted by the static
     * {@link #assignSamplerUnitsToBoundProgram} that the Embeddium terrain/shadow overrides call (their program
     * objects are Embeddium's, built lazily outside this class). Set on construction, cleared on destroy.
     */
    private static volatile Map<String, CustomTextureManager.Override> activeGbufferSamplerOverrides =
            java.util.Collections.emptyMap();
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
    /** The shadow-map pass, or {@code null} when the pack declares no {@code shadow} program. */
    private final IrisShadowRenderer shadowRenderer;

    /**
     * End-of-frame alt→main copy-back for a buffer the chain left odd-flipped (Iris FinalPassRenderer.SwapPass).
     * {@code from} is a read framebuffer over the buffer's ALT texture; the copy target is its MAIN texture.
     */
    private static final class SwapPass {
        final IrisFramebuffer from;
        final int targetTexture;
        final int index;

        SwapPass(int index, IrisFramebuffer from, int targetTexture) {
            this.index = index;
            this.from = from;
            this.targetTexture = targetTexture;
        }
    }

    private static final class ClearPass {
        final IrisFramebuffer framebuffer;
        final float[] color;

        ClearPass(IrisFramebuffer framebuffer, float[] color) {
            this.framebuffer = framebuffer;
            this.color = color;
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
    /** One-shot debug dump of the render targets, ~4s after the pipeline builds (0 = fired). */
    private int debugDumpCountdown = 240;
    /**
     * Frames left to probe {@code glGetError} around each composite pass (0 = off). Pinpoints which pass/step raises
     * the {@code 1282 Invalid operation} Minecraft's "Post render" check reports. Counts down over the opening frames.
     */
    private int glErrorProbeFrames = 60;

    public IrisRenderingPipeline(ShaderPack pack) {
        Minecraft mc = Minecraft.getMinecraft();
        this.renderTargets = new IrisRenderTargets(mc.displayWidth, mc.displayHeight);
        java.util.Arrays.fill(this.colorBufferClears, true);

        boolean initialized = false;
        try {
            this.quadRenderer = new FullscreenQuadRenderer();
            this.noiseTexture = new NoiseTexture(NoiseTexture.DEFAULT_RESOLUTION);
            this.defaultNormals = new PlainTexture(127, 127, 255, 255);
            this.defaultSpecular = new PlainTexture(0, 0, 0, 0);
            this.stubShadowMap = new StubShadowMap();

            List<ProgramSource> fullscreenSources = collectFullscreenSources(pack);
            applyPackFormatDirectives(fullscreenSources);
            materializeSampledTargets(fullscreenSources);

            // Publish the pack's block.properties mapping for the chunk meshers (null keeps raw 1.12.2 IDs). Done
            // here rather than at pack parse because registry resolution needs the game fully initialized.
            org.taumc.celeritas.iris.material.WorldRenderingSettings.setBlockStateIds(
                    org.taumc.celeritas.iris.material.BlockMaterialMapping.createBlockStateIdTable(pack.getIdMap()));

            // Custom images/textures must exist before any program compiles: sampler-unit assignment consults
            // the overrides (image uniforms are plain glUniform1i assignments like samplers).
            this.customTextureManager = new CustomTextureManager(pack, SAMPLER_UNITS,
                    CUSTOM_TEX_FIRST_UNIT, maxProgrammableTextureUnit());
            this.customImageManager = new CustomImageManager(pack.getProperties().getIrisCustomImages(),
                    this.customTextureManager.getNextAvailableUnit(), maxProgrammableTextureUnit());
            activeGbufferSamplerOverrides = mergedStageOverrides(TextureStage.GBUFFERS_AND_SHADOW);

            this.gbufferPrograms = new GbufferPrograms(pack, SAMPLER_UNITS, gbufferSamplerOverrideUnits());
            this.gbufferAttachments = computeGbufferAttachments(pack, terrainDrawBuffers(pack));
            for (int i = 0; i < this.gbufferAttachments.length; i++) {
                this.gbufferAttachmentPoints.put(this.gbufferAttachments[i], i);
            }
            this.shadowRenderer = createShadowRenderer(pack);
            buildComputePasses(pack);
            BufferFlipper flipper = this.renderTargets.getBufferFlipper();

            // Bake the single schedule from the reset flip state, then record which buffers the chain leaves
            // odd-flipped: those get an end-of-frame alt->main copy-back (Iris's SwapPass), so the next frame's
            // baked FBOs and sampler snapshots are valid again without any per-frame parity.
            buildSchedule(pack, flipper);
            buildSwapPasses(flipper);
            buildClearPasses();

            LOGGER.info("[Iris] Rendering pipeline ready: {} deferred + {} composite/final pass(es){}, {} swap(s), gbuffer {}x{}",
                    this.deferredPasses.size(), this.passes.size(),
                    this.blitSourceFramebuffer != null ? " + colortex0 blit" : "",
                    this.swapPasses.size(),
                    this.renderTargets.getWidth(), this.renderTargets.getHeight());
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

    // ------------------------------------------------------------------ construction

    private List<ProgramSource> collectFullscreenSources(ShaderPack pack) {
        List<ProgramSource> sources = new ArrayList<>();
        for (int i = 0; i < ProgramArrayId.Deferred.getNumPrograms(); i++) {
            pack.getProgramSet().get(ProgramArrayId.Deferred, i).ifPresent(sources::add);
        }
        for (int i = 0; i < ProgramArrayId.Composite.getNumPrograms(); i++) {
            pack.getProgramSet().get(ProgramArrayId.Composite, i).ifPresent(sources::add);
        }
        pack.getProgramSet().get(ProgramId.Final).ifPresent(sources::add);
        return sources;
    }

    /**
     * Applies the pack's render-target format directives ({@code const int colortex0Format = RGBA16;}, including the
     * legacy {@code gcolorFormat}-style names), the OptiFine way: declared as consts anywhere in the composite/
     * deferred/final sources. Must run before any target is materialized. HDR packs depend on this — with plain RGBA8
     * their tonemapping input is clamped and highlights blow out.
     */
    private void applyPackFormatDirectives(List<ProgramSource> sources) {
        Pattern formatDirective = Pattern.compile("const\\s+int\\s+(\\w+?)Format\\s*=\\s*(\\w+)\\s*;");
        Pattern clearDirective = Pattern.compile("const\\s+bool\\s+(\\w+?)Clear\\s*=\\s*(true|false)\\s*;");
        Pattern clearColorDirective = Pattern.compile("const\\s+vec4\\s+(\\w+?)ClearColor\\s*=\\s*vec4\\s*\\(([^)]*)\\)\\s*;");
        for (ProgramSource source : sources) {
            String[] stages = {source.getFragmentSource().orElse(null), source.getVertexSource().orElse(null)};
            for (String stage : stages) {
                if (stage == null) {
                    continue;
                }
                Matcher matcher = formatDirective.matcher(stage);
                while (matcher.find()) {
                    Integer index = SAMPLER_UNITS.get(matcher.group(1));
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
                    Integer index = SAMPLER_UNITS.get(clearMatcher.group(1));
                    if (index != null && index < IrisRenderTargets.MAX_COLOR_BUFFERS) {
                        this.colorBufferClears[index] = Boolean.parseBoolean(clearMatcher.group(2));
                    }
                }
                Matcher clearColorMatcher = clearColorDirective.matcher(stage);
                while (clearColorMatcher.find()) {
                    Integer index = SAMPLER_UNITS.get(clearColorMatcher.group(1));
                    if (index != null && index < IrisRenderTargets.MAX_COLOR_BUFFERS) {
                        float[] color = parseVec4(clearColorMatcher.group(2));
                        if (color != null) {
                            this.colorBufferClearColors[index] = color;
                        }
                    }
                }
            }
        }
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
        for (Map.Entry<String, Integer> entry : SAMPLER_UNITS.entrySet()) {
            int unit = entry.getValue();
            if (unit >= IrisRenderTargets.MAX_COLOR_BUFFERS) {
                continue; // only color targets are materialized here
            }
            if (Pattern.compile("\\bsampler2D\\s+" + entry.getKey() + "\\b").matcher(text).find()) {
                this.renderTargets.getOrCreate(unit);
            }
        }
    }

    /**
     * Builds the shadow renderer when the pack declares a {@code shadow} program, with {@code shadowMapResolution}
     * and {@code shadowDistance} parsed from the OptiFine const directives anywhere in the pack sources.
     */
    private IrisShadowRenderer createShadowRenderer(ShaderPack pack) {
        // Const directives may live in ANY source (packs put them in shared includes flattened into every program),
        // so scan the gbuffer programs and the whole fullscreen chain.
        StringBuilder allSources = new StringBuilder();
        for (ProgramId id : new ProgramId[]{ProgramId.Shadow, ProgramId.Terrain, ProgramId.Water, ProgramId.Final}) {
            pack.getProgramSet().get(id).ifPresent(source -> {
                source.getVertexSource().ifPresent(allSources::append);
                source.getFragmentSource().ifPresent(allSources::append);
            });
        }
        for (ProgramSource source : collectFullscreenSources(pack)) {
            source.getFragmentSource().ifPresent(allSources::append);
        }
        String text = allSources.toString();

        // Tilt of the sun/moon's daily arc. Needed by the celestial-position uniforms whether or not the pack draws
        // shadows, so set it before the no-shadow early-out.
        float sunPathRotation = parseConstFloat(text, "sunPathRotation", 0.0f);
        CelestialUniforms.setSunPathRotation(sunPathRotation);

        Optional<ProgramSource> shadowSource = pack.getProgramSet().get(ProgramId.Shadow);
        if (!shadowSource.isPresent()) {
            LOGGER.info("[Iris] Pack declares no shadow program; shadow mapping disabled (always-lit stub in use)");
            return null;
        }
        int resolution = parseConstInt(text, "shadowMapResolution", 1024);
        float distance = parseConstFloat(text, "shadowDistance", 120.0f);
        // OptiFine's hardware-compare contract: `const bool shadowHardwareFiltering` covers both shadow depth
        // textures; the 0/1 forms cover one each. LIGHT declares ...Filtering0, Complementary the both-textures form.
        boolean hwBoth = parseConstBool(text, "shadowHardwareFiltering");
        boolean[] hardwareFiltering = {
                hwBoth || parseConstBool(text, "shadowHardwareFiltering0"),
                hwBoth || parseConstBool(text, "shadowHardwareFiltering1")
        };
        // The FF shadow program (entities/block entities) belongs to the gbuffers custom-texture stage.
        Map<String, Integer> shadowSamplerUnits = new LinkedHashMap<>(SAMPLER_UNITS);
        shadowSamplerUnits.putAll(gbufferSamplerOverrideUnits());
        try {
            return new IrisShadowRenderer(resolution, distance, sunPathRotation,
                    shadowSource.get(), shadowSamplerUnits, hardwareFiltering, this::bindShaderPackResources);
        } catch (Exception e) {
            LOGGER.error("[Iris] Failed to create the shadow renderer; shadows disabled", e);
            return null;
        }
    }

    private static int parseConstInt(String text, String name, int fallback) {
        Matcher matcher = Pattern.compile("const\\s+int\\s+" + name + "\\s*=\\s*(\\d+)").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private static boolean parseConstBool(String text, String name) {
        return Pattern.compile("const\\s+bool\\s+" + name + "\\s*=\\s*true").matcher(text).find();
    }

    private static float parseConstFloat(String text, String name, float fallback) {
        // Allow a leading sign (sunPathRotation is often negative) and an optional f/F suffix (e.g. -40.0f).
        Matcher matcher = Pattern.compile("const\\s+float\\s+" + name + "\\s*=\\s*(-?[0-9]+(?:\\.[0-9]+)?)[fF]?").matcher(text);
        return matcher.find() ? Float.parseFloat(matcher.group(1)) : fallback;
    }

    /** The color buffers the terrain program writes, per its {@code DRAWBUFFERS} directive. */
    private int[] terrainDrawBuffers(ShaderPack pack) {
        int[] drawBuffers = pack.getProgramSet().get(ProgramId.Terrain)
                .flatMap(ProgramSource::getFragmentSource)
                .map(DrawBuffers::parseActive)
                .orElse(DrawBuffers.DEFAULT.clone());
        return sanitizeDrawBuffers("gbuffers_terrain", drawBuffers);
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
        int[] waterDrawBuffers = pack.getProgramSet().get(ProgramId.Water)
                .flatMap(ProgramSource::getFragmentSource)
                .map(DrawBuffers::parseActive)
                .orElse(DrawBuffers.DEFAULT.clone());
        for (int buffer : sanitizeDrawBuffers("gbuffers_water", waterDrawBuffers)) {
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
        return framebuffer;
    }

    private void drawGbufferBuffers(IrisFramebuffer framebuffer, int[] logicalDrawBuffers) {
        int[] physicalDrawBuffers = new int[logicalDrawBuffers.length];
        for (int i = 0; i < logicalDrawBuffers.length; i++) {
            Integer attachmentPoint = this.gbufferAttachmentPoints.get(logicalDrawBuffers[i]);
            if (attachmentPoint == null) {
                LOGGER.warn("[Iris] Gbuffer draw buffer colortex{} is not attached; routing output slot {} to colortex0",
                        logicalDrawBuffers[i], i);
                attachmentPoint = this.gbufferAttachmentPoints.get(0);
            }
            physicalDrawBuffers[i] = attachmentPoint == null ? 0 : attachmentPoint;
        }
        framebuffer.drawBuffers(physicalDrawBuffers);
    }

    /** Bakes one frame's ping-pong schedule from the flipper's current state, advancing the flipper as it goes. */
    private void buildSchedule(ShaderPack pack, BufferFlipper flipper) {
        this.gbufferFramebuffer = createGbufferFramebuffer(flipper);

        for (int i = 0; i < ProgramArrayId.Deferred.getNumPrograms(); i++) {
            Optional<ProgramSource> source = pack.getProgramSet().get(ProgramArrayId.Deferred, i);
            if (!source.isPresent()) {
                continue;
            }
            FullscreenPass pass = buildCompositePass(pack, source.get(), flipper);
            if (pass != null) {
                this.deferredPasses.add(pass);
            }
        }
        this.translucentGbufferFramebuffer =
                this.deferredPasses.isEmpty() ? this.gbufferFramebuffer : createGbufferFramebuffer(flipper);

        for (int i = 0; i < ProgramArrayId.Composite.getNumPrograms(); i++) {
            Optional<ProgramSource> source = pack.getProgramSet().get(ProgramArrayId.Composite, i);
            if (!source.isPresent()) {
                continue;
            }
            FullscreenPass pass = buildCompositePass(pack, source.get(), flipper);
            if (pass != null) {
                this.passes.add(pass);
            }
        }

        FullscreenPass finalPass = buildFinalPass(pack, flipper);
        if (finalPass != null) {
            this.passes.add(finalPass);
        } else {
            // No (working) final program: show the post-composite colortex0 by blitting it to the screen.
            this.blitSourceFramebuffer = new IrisFramebuffer();
            this.blitSourceFramebuffer.addColorAttachment(0, frontTexture(flipper, 0));
            this.blitSourceFramebuffer.readBuffer(0);
        }
    }

    /**
     * Iris {@code FinalPassRenderer.SwapPass}: every buffer the chain leaves odd-flipped ends the frame with its
     * latest content on the ALT side, so copy alt→main after the final pass. Buffers attached to the gbuffer FBO are
     * skipped — they are cleared and fully rewritten from the main side every frame, so carrying their content over
     * is pointless (Iris likewise skips its to-be-cleared buffers).
     */
    private void buildSwapPasses(BufferFlipper flipper) {
        for (int i = 0; i < IrisRenderTargets.MAX_COLOR_BUFFERS; i++) {
            if (!flipper.isFlipped(i) || this.renderTargets.get(i) == null || this.colorBufferClears[i]) {
                continue;
            }
            IrisRenderTarget target = this.renderTargets.get(i);
            IrisFramebuffer from = new IrisFramebuffer();
            from.addColorAttachment(0, target.getAltTexture());
            from.readBuffer(0);
            this.swapPasses.add(new SwapPass(i, from, target.getMainTexture()));
            LOGGER.debug("[Iris] colortex{} ends the frame odd-flipped; swap pass (alt->main copy) added", i);
        }
    }

    private void buildClearPasses() {
        for (int i = 0; i < IrisRenderTargets.MAX_COLOR_BUFFERS; i++) {
            if (this.renderTargets.get(i) == null) {
                continue;
            }
            float[] color = defaultClearColor(i);
            this.fullClearPasses.add(new ClearPass(
                    this.renderTargets.createClearFramebuffer(false, new int[]{i}), color));
            this.fullClearPasses.add(new ClearPass(
                    this.renderTargets.createClearFramebuffer(true, new int[]{i}), color));
            if (this.colorBufferClears[i]) {
                this.clearPasses.add(new ClearPass(
                        this.renderTargets.createClearFramebuffer(false, new int[]{i}), color));
                this.clearPasses.add(new ClearPass(
                        this.renderTargets.createClearFramebuffer(true, new int[]{i}), color));
            }
        }
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

    /**
     * Compiles a fullscreen program (and its uniforms) once and caches it by source name. Returns {@code null} if
     * the program failed to compile (cached as absent so we don't retry).
     */
    private IrisProgram cachedProgram(ProgramSource source) {
        String name = source.getName();
        if (this.compiledPrograms.containsKey(name)) {
            return this.compiledPrograms.get(name);
        }
        // deferredN belongs to the "deferred" custom-texture stage; compositeN and final to "composite".
        TextureStage stage = name.startsWith("deferred") ? TextureStage.DEFERRED : TextureStage.COMPOSITE_AND_FINAL;
        IrisProgram program = compileFullscreenProgram(source, stage);
        this.compiledPrograms.put(name, program);
        if (program != null) {
            this.compiledUniforms.put(name, buildUniforms(name, program));
        }
        return program;
    }

    private FullscreenPass buildCompositePass(ShaderPack pack, ProgramSource source, BufferFlipper flipper) {
        String name = source.getName();
        try {
            IrisProgram program = cachedProgram(source);
            if (program == null) {
                return null;
            }
            int[] drawBuffers = sanitizeCompositeDrawBuffers(name, program.getDrawBuffers());

            // Reads see the current "front" side; the FBO writes the back side; then the written buffers flip.
            int[] colorSamplers = snapshotFrontTextures(flipper);
            IrisFramebuffer framebuffer = this.renderTargets.createColorFramebuffer(drawBuffers);
            for (int buffer : drawBuffers) {
                flipper.flip(buffer);
                // Later passes' colortex custom-texture overrides deactivate for buffers a pass has written.
                this.flippedAtLeastOnce.add(buffer);
            }

            return new FullscreenPass(name, program, this.compiledUniforms.get(name), framebuffer, colorSamplers,
                    drawBuffers, ProgramBlendState.from(pack.getProperties(), name));
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
        try {
            IrisProgram program = cachedProgram(source.get());
            if (program == null) {
                return null;
            }
            return new FullscreenPass(name, program, this.compiledUniforms.get(name), null,
                    snapshotFrontTextures(flipper), DrawBuffers.DEFAULT.clone(),
                    ProgramBlendState.from(pack.getProperties(), name));
        } catch (Exception e) {
            LOGGER.error("[Iris] Failed to build final pass; falling back to colortex0 blit: {}", e.getMessage());
            return null;
        }
    }

    private IrisProgram compileFullscreenProgram(ProgramSource source, TextureStage stage) {
        String vshRaw = source.getVertexSource().orElse(null);
        String fshRaw = source.getFragmentSource().orElse(null);
        if (vshRaw == null || fshRaw == null) {
            LOGGER.warn("[Iris] Program '{}' is missing a vertex or fragment stage; skipping", source.getName());
            return null;
        }

        GlShader vertex = null;
        GlShader fragment = null;
        try {
            // Modern packs (#version 130+ single-source, e.g. Complementary) get the minimal-touch transform; the
            // GLSL-120 Chocapic family (LIGHT) keeps the full 330-core rewrite. Detect off the fragment source.
            boolean modern = ModernPackTransformer.isModernSource(fshRaw);
            this.modernPack |= modern;
            String vsh;
            String fsh;
            if (modern) {
                // Modern sources rely on the driver preprocessor for their #if trees; the MC_*/IRIS_FEATURE_* macro
                // environment has to be present for those gates (colored lighting checks IRIS_FEATURE_CUSTOM_IMAGES).
                Map<String, String> macros = org.taumc.celeritas.iris.gl.shader.ShaderMacros.standard();
                vsh = ModernPackTransformer.transform(
                        org.taumc.celeritas.iris.gl.shader.ShaderMacros.injectDefines(vshRaw, macros));
                fsh = ModernPackTransformer.transform(
                        org.taumc.celeritas.iris.gl.shader.ShaderMacros.injectDefines(fshRaw, macros));
            } else {
                vsh = FullscreenTransformer.transformVertexShader(vshRaw);
                fsh = FullscreenTransformer.transformFragmentShader(fshRaw);
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
                // The 330-core LIGHT path writes to an explicit out array; modern packs use the compatibility
                // gl_FragData[] built-in, which the driver already maps to draw buffers 0..n.
                builder.bindFragmentDataLocation(0, "iris_FragData");
            }
            GlProgram program = builder.link();

            assignSamplerUnits(program, stage);
            return new IrisProgram(program, DrawBuffers.parseActive(fshRaw));
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
        assignSamplerUnits(program.getGlId(), mergedStageOverrides(stage), this.flippedAtLeastOnce);
        program.unbind();
    }

    /**
     * Assigns the standard sampler-unit mapping on the <em>currently bound</em> program (raw GL id), with the active
     * pipeline's gbuffers/shadow-stage custom-texture overrides. Used by the Embeddium terrain and shadow overrides,
     * whose program objects are Embeddium's rather than ours — both belong to the {@code gbuffers} texture stage.
     */
    public static void assignSamplerUnitsToBoundProgram(int programId) {
        assignSamplerUnits(programId, activeGbufferSamplerOverrides, java.util.Collections.<Integer>emptySet());
    }

    private static void assignSamplerUnits(int programId, Map<String, CustomTextureManager.Override> overrides,
                                           java.util.Set<Integer> flippedAtLeastOnce) {
        for (Map.Entry<String, Integer> entry : SAMPLER_UNITS.entrySet()) {
            int location = LWJGL.glGetUniformLocation(programId, entry.getKey());
            if (location == -1) {
                continue;
            }
            int unit = entry.getValue();
            CustomTextureManager.Override override = overrides.get(entry.getKey());
            if (override != null && (override.colorTarget < 0 || !flippedAtLeastOnce.contains(override.colorTarget))) {
                unit = override.unit;
            }
            LWJGL.glUniform1i(location, unit);
        }
        // Pack-declared sampler names with no standard unit (customTexture.<name> directives).
        for (Map.Entry<String, CustomTextureManager.Override> entry : overrides.entrySet()) {
            if (SAMPLER_UNITS.containsKey(entry.getKey())) {
                continue;
            }
            int location = LWJGL.glGetUniformLocation(programId, entry.getKey());
            if (location != -1) {
                LWJGL.glUniform1i(location, entry.getValue().unit);
            }
        }
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

    /**
     * The stage's custom-texture overrides plus the (stage-independent) custom-image uniform assignments, in the
     * Override form {@link #assignSamplerUnits} consumes. Image entries never deactivate (colorTarget -1).
     */
    private Map<String, CustomTextureManager.Override> mergedStageOverrides(TextureStage stage) {
        Map<String, CustomTextureManager.Override> merged =
                new LinkedHashMap<>(this.customTextureManager.getOverrides(stage));
        this.customImageManager.getUniformOverrides().forEach((name, unit) ->
                merged.put(name, new CustomTextureManager.Override(unit, -1)));
        return merged;
    }

    private static ProgramUniforms buildUniforms(String name, IrisProgram program) {
        ProgramUniforms.Builder builder = ProgramUniforms.builder(name, program.getProgram().getGlId());
        CommonUniforms.addCommonUniforms(builder);
        MatrixUniforms.addMatrixUniforms(builder);
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
        List<Integer> valid = new ArrayList<>();
        for (int buffer : drawBuffers) {
            if (buffer >= 0 && buffer < maxExclusive) {
                valid.add(buffer);
            } else {
                LOGGER.debug("[Iris] '{}' declares draw buffer {} (max {} here; usually an inactive #ifdef path); ignoring it",
                        name, buffer, maxExclusive - 1);
            }
        }
        if (valid.isEmpty()) {
            return DrawBuffers.DEFAULT.clone();
        }
        int[] result = new int[valid.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = valid.get(i);
        }
        return result;
    }

    // ------------------------------------------------------------------ per-frame hooks

    private void runClearPasses(List<ClearPass> passes) {
        for (ClearPass pass : passes) {
            pass.framebuffer.bind();
            LWJGL.glViewport(0, 0, this.renderTargets.getWidth(), this.renderTargets.getHeight());
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


        // noisetex and the stub shadow maps ride along for the whole frame (gbuffer + fullscreen stages) on their
        // fixed units; vanilla never binds units above 1, and GlStateManager's 8-slot cache can't address them.
        // A pack-supplied texture.noise replaces the generated noise (Iris CustomTextureManager parity).
        int customNoise = this.customTextureManager.getNoiseTextureId();
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + NOISE_TEX_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, customNoise != -1 ? customNoise : this.noiseTexture.getTextureId());
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_TEX_0_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.stubShadowMap.getTextureId());
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_TEX_1_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.stubShadowMap.getTextureId());
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0);

        // Custom images: zero the per-frame ones (voxel volume), then bind image units + paired samplers.
        this.customImageManager.clearAll();
        bindShaderPackResources();

        // Default PBR maps on the gbuffer-stage normals/specular units (2/3, so through GlStateManager to keep its
        // cache coherent). The composite stage overwrites these units with colortex2/3 when it runs.
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + 2);
        GlStateManager.bindTexture(this.defaultNormals.getTextureId());
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + 3);
        GlStateManager.bindTexture(this.defaultSpecular.getTextureId());
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);

        IrisFramebuffer gbuffer = this.gbufferFramebuffer;
        this.currentGbuffer = gbuffer;
        runClearPasses(this.fullClearRequired ? this.fullClearPasses : this.clearPasses);
        this.fullClearRequired = false;
        gbuffer.bind();
        drawGbufferBuffers(gbuffer, FIXED_FUNCTION_MASK);
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

    public void setPhase(ProgramId phase) {
        if (!this.worldRenderingActive) {
            return;
        }
        boolean sky = phase == ProgramId.SkyBasic || phase == ProgramId.SkyTextured;
        if (sky != this.skyAtFarPlane) {
            LWJGL.glDepthRange(sky ? 1.0 : 0.0, 1.0);
            this.skyAtFarPlane = sky;
        }
        GbufferPrograms.Entry entry = phase == null ? null : this.gbufferPrograms.get(phase);
        if (entry == null) {
            LWJGL.glUseProgram(0);
            drawGbufferBuffers(this.currentGbuffer, FIXED_FUNCTION_MASK);
        } else {
            entry.getProgram().bind();
            bindShaderPackResources();
            entry.getUniforms().update();
            drawGbufferBuffers(this.currentGbuffer, entry.getDrawBuffers());
            entry.getBlendState().apply(entry.getDrawBuffers());
        }
    }

    /**
     * Called when the Embeddium terrain override program binds ({@code IrisTerrainShaderInterface.setupState}): points
     * the gbuffer's draw-buffer mask at the terrain/water program's {@code DRAWBUFFERS} directive.
     */
    public void onTerrainDraw(int[] drawBuffers, ProgramBlendState blendState) {
        if (!this.worldRenderingActive) {
            return;
        }
        drawGbufferBuffers(this.currentGbuffer, drawBuffers);
        blendState.apply(drawBuffers);
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
     */
    public void renderShadowMap() {
        if (!this.worldRenderingActive || this.shadowRenderer == null) {
            return;
        }
        this.shadowRenderer.render();
        dispatchComputePasses();

        this.currentGbuffer.bind();
        LWJGL.glViewport(0, 0, this.renderTargets.getWidth(), this.renderTargets.getHeight());

        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_TEX_0_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowRenderer.getDepthTextureId());
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_TEX_1_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowRenderer.getDepthTextureNoTranslucentsId());
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_COLOR_0_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowRenderer.getColorTextureId());
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_COLOR_1_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowRenderer.getColorTexture1Id());
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
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
        GlStateManager.depthMask(true);
    }

    /** Called at the {@code "hand"} profiler anchor: snapshot the pre-hand depth ({@code depthtex2}). */
    public void beginHand() {
        if (!this.worldRenderingActive) {
            return;
        }
        copyDepthTexture(this.renderTargets.getDepthTextureNoHand());
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
        Minecraft mc = Minecraft.getMinecraft();

        // Full-screen passes draw with depth/blend/alpha-test off. Going through GlStateManager keeps its state cache
        // coherent with reality, so vanilla's later enable/disable calls are not silently skipped.
        GlStateManager.disableBlend();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableAlpha();

        bindDepthSamplers();

        // Flicker debugging: the last TWO countdown frames dump as suffix _A then _B, so consecutive frames of the
        // same buffers can be diffed offline — anything alternating per frame (TAA jitter, dither parity, floodfill
        // ping-pong) shows up as the A/B difference. The one-shot extras (shadow map, noise) dump on frame A only.
        String dumpSuffix = null;
        if (this.debugDumpCountdown > 0) {
            this.debugDumpCountdown--;
            if (this.debugDumpCountdown == 1) {
                dumpSuffix = "_A";
            } else if (this.debugDumpCountdown == 0) {
                dumpSuffix = "_B";
            }
        }
        boolean dumpThisFrame = dumpSuffix != null;
        boolean dumpExtras = "_A".equals(dumpSuffix);
        if (dumpThisFrame) {
            int width = this.renderTargets.getWidth();
            int height = this.renderTargets.getHeight();
            // BOTH ping-pong sides of the flicker-relevant targets, so offline diffs can compare fresh against
            // stale content regardless of each buffer's flip state at dump time.
            IrisDebugDump.dumpColorTexture("colortex0_main" + dumpSuffix, this.renderTargets.getOrCreate(0).getMainTexture(), width, height);
            IrisDebugDump.dumpColorTexture("colortex0_alt" + dumpSuffix, this.renderTargets.getOrCreate(0).getAltTexture(), width, height);
            IrisDebugDump.dumpColorTexture("colortex2_main" + dumpSuffix, this.renderTargets.getOrCreate(2).getMainTexture(), width, height);
            IrisDebugDump.dumpColorTexture("colortex2_alt" + dumpSuffix, this.renderTargets.getOrCreate(2).getAltTexture(), width, height);
            if (dumpExtras) {
                IrisDebugDump.dumpColorTexture("colortex1_gbuffer", this.renderTargets.getOrCreate(1).getMainTexture(), width, height);
                IrisDebugDump.dumpDepthTexture("depthtex0", this.renderTargets.getDepthTexture().getTextureId(), width, height);
                if (this.shadowRenderer != null) {
                    int resolution = this.shadowRenderer.getResolution();
                    IrisDebugDump.dumpDepthTexture("shadowtex0", this.shadowRenderer.getDepthTextureId(), resolution, resolution);
                    IrisDebugDump.dumpColorTexture("shadowcolor0", this.shadowRenderer.getColorTextureId(), resolution, resolution);
                }
                IrisDebugDump.dumpColorTexture("noisetex", this.noiseTexture.getTextureId(),
                        NoiseTexture.DEFAULT_RESOLUTION, NoiseTexture.DEFAULT_RESOLUTION);
                // Numeric state for the celestial/ambient debugging: the composite VSH derives its day factors from these.
                org.joml.Vector3f sun = org.taumc.celeritas.iris.uniforms.CelestialUniforms.getSunPosition();
                org.joml.Vector3f up = org.taumc.celeritas.iris.uniforms.CelestialUniforms.getUpPosition();
                float sdotu = new org.joml.Vector3f(sun).normalize().dot(new org.joml.Vector3f(up).normalize());
                LOGGER.info("[Iris] Debug state: sunPosition={} upPosition={} SdotU={} celestialAngle={} eyeBrightnessSmooth={} fogColor={}",
                        sun, up, sdotu,
                        org.taumc.celeritas.iris.uniforms.CelestialUniforms.getCelestialAngle(),
                        EyeBrightnessTracker.getEyeBrightnessSmooth(),
                        CapturedRenderingState.INSTANCE.getFogColor());
            }
            // Colored-lighting floodfill convergence check: the shadowcomp compute scrolls the voxel volume every
            // frame by posOffset = floor(previousCameraPosition) - floor(cameraPosition). If that is anything but
            // (0,0,0) on a still camera (e.g. camera Y jittering across an integer while standing on a snow layer),
            // the volume re-scrolls each frame and the floodfill never converges -> block-edge pulsation.
            org.joml.Vector3d cam = CapturedRenderingState.INSTANCE.getCameraPosition();
            org.joml.Vector3d prevCam = CapturedRenderingState.INSTANCE.getPreviousCameraPosition();
            long offX = (long) Math.floor(prevCam.x) - (long) Math.floor(cam.x);
            long offY = (long) Math.floor(prevCam.y) - (long) Math.floor(cam.y);
            long offZ = (long) Math.floor(prevCam.z) - (long) Math.floor(cam.z);
            LOGGER.info("[Iris] Flicker probe frame {}: frameCounter={} framemod8={} cam=({},{},{}) prevCam=({},{},{}) voxelScroll=({},{},{})",
                    dumpSuffix, SystemTimeUniforms.COUNTER.getFrameCounter(),
                    SystemTimeUniforms.COUNTER.getFrameCounter() & 7,
                    cam.x, cam.y, cam.z, prevCam.x, prevCam.y, prevCam.z, offX, offY, offZ);
        }

        for (FullscreenPass pass : this.passes) {
            runPass(pass, mc);
        }

        if (dumpThisFrame && !this.passes.isEmpty()) {
            FullscreenPass lastPass = this.passes.get(this.passes.size() - 1);
            int colortex4 = lastPass.colorSamplers[4] != 0
                    ? lastPass.colorSamplers[4] : this.renderTargets.getOrCreate(4).getMainTexture();
            IrisDebugDump.dumpColorTexture("colortex4_postcomposite" + dumpSuffix, colortex4,
                    this.renderTargets.getWidth(), this.renderTargets.getHeight());
        }

        if (this.blitSourceFramebuffer != null) {
            this.blitSourceFramebuffer.bindAsReadBuffer();
            int target = OpenGlHelper.isFramebufferEnabled() ? mc.getFramebuffer().framebufferObject : 0;
            LWJGL.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target);
            LWJGL.glBlitFramebuffer(0, 0, this.renderTargets.getWidth(), this.renderTargets.getHeight(),
                    0, 0, mc.displayWidth, mc.displayHeight,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        }

        // Iris FinalPassRenderer.SwapPass: buffers the chain leaves odd-flipped carry this frame's data on their
        // ALT side — copy it back to MAIN so next frame's baked FBOs and sampler snapshots read fresh data. NB:
        // glCopyTexSubImage2D reads the GL_READ_BUFFER of the framebuffer bound to GL_FRAMEBUFFER (bind(), not
        // bindAsReadBuffer() — Iris hit TAA breakage on many drivers with the read-framebuffer binding).
        for (SwapPass swap : this.swapPasses) {
            swap.from.bind();
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, swap.targetTexture);
            LWJGL.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0,
                    this.renderTargets.getWidth(), this.renderTargets.getHeight());
        }
        if (!this.swapPasses.isEmpty()) {
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }

        // Hand control back to vanilla: its framebuffer bound, no program/VAO, texture units cleaned up.
        LWJGL.glUseProgram(0);
        bindMainRenderTarget(mc);
        restoreMainDrawReadBuffers(mc);
        restoreTextureUnits();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();

        CapturedRenderingState.INSTANCE.rollOverPreviousFrame();
    }

    public boolean isWorldRenderingActive() {
        return this.worldRenderingActive;
    }

    public boolean shouldDisableVanillaEntityShadows() {
        return this.shadowRenderer != null;
    }

    /**
     * Compiles the pack's shadowcomp compute passes ({@code .csh}, Iris extension — Complementary's floodfill light
     * propagation). The dispatch size is the first 3D custom image's dimensions divided by the shader's declared
     * {@code local_size} (exactly the {@code const ivec3 workGroups} Complementary declares per volume size).
     */
    private void buildComputePasses(ShaderPack pack) {
        int[] volume = this.customImageManager.getFirst3DImageSize();
        for (int i = 0; i < ProgramArrayId.ShadowComposite.getNumPrograms(); i++) {
            Optional<ProgramSource> source = pack.getProgramSet().get(ProgramArrayId.ShadowComposite, i);
            if (!source.isPresent() || !source.get().getComputeSource().isPresent()) {
                continue;
            }
            String name = source.get().getName();
            try {
                String csh = org.taumc.celeritas.iris.gl.shader.ShaderMacros.injectDefines(
                        source.get().getComputeSource().get(),
                        org.taumc.celeritas.iris.gl.shader.ShaderMacros.standard());
                IrisDebugDump.dumpText("src_" + name + ".csh", csh);
                int[] localSize = parseLocalSize(csh);
                if (volume == null || localSize == null) {
                    LOGGER.warn("[Iris] Compute pass '{}' skipped (no 3D custom image / local_size declaration)", name);
                    continue;
                }
                GlShader shader = new GlShader(ShaderType.COMPUTE, name + ".csh", csh);
                GlProgram program;
                try {
                    program = ProgramBuilder.begin(name).attach(shader).link();
                } finally {
                    shader.destroy();
                }
                program.bind();
                assignSamplerUnits(program.getGlId(),
                        mergedStageOverrides(TextureStage.SHADOWCOMP), java.util.Collections.<Integer>emptySet());
                program.unbind();
                ProgramUniforms.Builder uniforms = ProgramUniforms.builder(name, program.getGlId());
                CommonUniforms.addCommonUniforms(uniforms);
                MatrixUniforms.addMatrixUniforms(uniforms);
                int groupsX = Math.max(1, volume[0] / localSize[0]);
                int groupsY = Math.max(1, volume[1] / localSize[1]);
                int groupsZ = Math.max(1, volume[2] / localSize[2]);
                this.computePasses.add(new ComputePass(name, program, uniforms.buildUniforms(),
                        groupsX, groupsY, groupsZ));
                LOGGER.info("[Iris] Compute pass '{}' ready: dispatch {}x{}x{} (local {}x{}x{})",
                        name, groupsX, groupsY, groupsZ, localSize[0], localSize[1], localSize[2]);
            } catch (Exception e) {
                LOGGER.error("[Iris] Failed to build compute pass '{}'; it will be skipped: {}", name, e.getMessage());
            }
        }
    }

    private static int[] parseLocalSize(String source) {
        Matcher matcher = Pattern.compile(
                "local_size_x\\s*=\\s*(\\d+)(?:\\s*,\\s*local_size_y\\s*=\\s*(\\d+))?(?:\\s*,\\s*local_size_z\\s*=\\s*(\\d+))?")
                .matcher(source);
        if (!matcher.find()) {
            return null;
        }
        int x = Integer.parseInt(matcher.group(1));
        int y = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 1;
        int z = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 1;
        return new int[]{x, y, z};
    }

    /**
     * Runs the shadowcomp compute chain: a full barrier makes the shadow pass's imageStore voxelization visible,
     * each pass dispatches, and a closing barrier publishes the results to every later sampler read.
     */
    private void dispatchComputePasses() {
        if (this.computePasses.isEmpty()) {
            return;
        }
        // Iris rebinds all images + paired samplers at every compute use (ComputeProgram.use -> images.update()). The
        // Embeddium shadow-terrain draw that just voxelized runs through managed code that can reset texture/image
        // units, so re-establish the voxel/floodfill bindings here rather than trusting the frame-start bindAll to
        // survive it — otherwise the compute could read/write the wrong (or unbound) volume.
        bindShaderPackResources();
        LWJGL.glMemoryBarrier(org.taumc.celeritas.lwjgl.GL42.GL_ALL_BARRIER_BITS);
        for (ComputePass pass : this.computePasses) {
            pass.program.bind();
            bindShaderPackResources();
            pass.uniforms.update();
            LWJGL.glDispatchCompute(pass.groupsX, pass.groupsY, pass.groupsZ);
            // Each floodfill iteration reads the previous one's writes.
            LWJGL.glMemoryBarrier(org.taumc.celeritas.lwjgl.GL42.GL_ALL_BARRIER_BITS);
        }
        LWJGL.glUseProgram(0);
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

    /** Runs one full-screen pass into its framebuffer (or Minecraft's framebuffer for the final pass). */
    private void runPass(FullscreenPass pass, Minecraft mc) {
        if (pass.framebuffer != null) {
            pass.framebuffer.bind();
            LWJGL.glViewport(0, 0, this.renderTargets.getWidth(), this.renderTargets.getHeight());
        } else {
            bindMainRenderTarget(mc);
            restoreMainDrawReadBuffers(mc);
        }
        GlStateManager.disableBlend();
        pass.blendState.apply(pass.drawBuffers);
        bindColorSamplers(pass);
        pass.program.bind();
        bindShaderPackResources();
        pass.uniforms.update();
        boolean probe = this.glErrorProbeFrames > 0;
        if (probe) {
            drainGlError(); // clear anything vanilla/Embeddium left so we only attribute this pass's own errors
        }
        if (this.modernPack) {
            // ftransform() = projection*modelview*gl_Vertex and (gl_TextureMatrix[0]*gl_MultiTexCoord0) must be
            // identities so the [-1,1] quad and its [0,1] texcoords pass straight through. Save/restore so the hand
            // and GUI that vanilla draws after the composite chain are unaffected.
            pushIdentityFixedFunctionMatrices();
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

    private static void pushIdentityFixedFunctionMatrices() {
        GlStateManager.matrixMode(GL_PROJECTION_MODE);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
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
     * Copies the depth of the currently-bound (gbuffer) framebuffer into {@code destination} — how OptiFine snapshots
     * {@code depthtex1}/{@code depthtex2}. Runs on a scratch texture unit so no vanilla-tracked binding is disturbed.
     */
    private void copyDepthTexture(DepthTexture destination) {
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
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_COPY_SCRATCH_UNIT);
        int previousTexture = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        return previousTexture;
    }

    private static void restoreScratchTexture2D(int texture) {
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
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
                if (i < 8) {
                    // Units 0..7 go through GlStateManager so vanilla's texture-unit cache stays coherent.
                    GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + i);
                    GlStateManager.bindTexture(pass.colorSamplers[i]);
                } else {
                    // colortex8..15 live beyond GlStateManager's 8-slot cache (indexing it there throws); vanilla
                    // never touches these units, so a raw bind is correct and safe.
                    LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + i);
                    LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, pass.colorSamplers[i]);
                }
            }
        }
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
    }

    private void bindDepthSamplers() {
        // Units 8+ are beyond GlStateManager's 8-slot cache and untouched by vanilla, so raw binds are safe here.
        bindDepthSampler(DEPTH_TEX_0_UNIT, this.renderTargets.getDepthTexture());
        bindDepthSampler(DEPTH_TEX_1_UNIT, this.renderTargets.getDepthTextureNoTranslucents());
        bindDepthSampler(DEPTH_TEX_2_UNIT, this.renderTargets.getDepthTextureNoHand());
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private static void bindDepthSampler(int unit, DepthTexture texture) {
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture.getTextureId());
    }

    private void restoreTextureUnits() {
        this.customTextureManager.unbindAll();
        this.customImageManager.unbindAll();
        for (int unit = DEPTH_TEX_0_UNIT; unit <= DEPTH_TEX_2_UNIT; unit++) {
            LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
        for (int unit : new int[]{SHADOW_COLOR_0_UNIT, SHADOW_COLOR_1_UNIT, SHADOW_TEX_0_UNIT, SHADOW_TEX_1_UNIT, NOISE_TEX_UNIT}) {
            LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
        // colortex8..15 sit beyond GlStateManager's 8-slot cache — unbind those with raw GL (indexing the cache at
        // unit 8+ throws ArrayIndexOutOfBounds); units 0..7 go through GlStateManager to keep its cache coherent.
        for (int i = IrisRenderTargets.MAX_COLOR_BUFFERS - 1; i >= 8; i--) {
            LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + i);
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
        for (int i = 7; i >= 0; i--) {
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + i);
            GlStateManager.bindTexture(0);
        }
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

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
        org.taumc.celeritas.iris.terrain.IrisTerrainProgramOverride.destroyShadowPrograms();
        if (this.shadowRenderer != null) {
            this.shadowRenderer.destroy();
        }
        if (this.gbufferPrograms != null) {
            this.gbufferPrograms.destroy();
        }
        activeGbufferSamplerOverrides = java.util.Collections.emptyMap();
        org.taumc.celeritas.iris.material.WorldRenderingSettings.setBlockStateIds(null);
        for (ComputePass pass : this.computePasses) {
            pass.program.destroy();
        }
        this.computePasses.clear();
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
        if (this.stubShadowMap != null) {
            this.stubShadowMap.destroy();
        }
        this.renderTargets.destroy();
    }
}
