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
import org.taumc.celeritas.iris.targets.BufferFlipper;
import org.taumc.celeritas.iris.targets.DepthTexture;
import org.taumc.celeritas.iris.targets.IrisRenderTarget;
import org.taumc.celeritas.iris.targets.IrisRenderTargets;
import org.taumc.celeritas.iris.targets.NoiseTexture;
import org.taumc.celeritas.iris.terrain.FullscreenTransformer;
import org.taumc.celeritas.iris.uniforms.CapturedRenderingState;
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

    // Texture units: colortex0..7 occupy units 0..7; the rest live above them, clear of anything vanilla touches.
    private static final int DEPTH_TEX_0_UNIT = 8;
    private static final int DEPTH_TEX_1_UNIT = 9;
    private static final int DEPTH_TEX_2_UNIT = 10;
    private static final int NOISE_TEX_UNIT = 15;
    private static final int SHADOW_TEX_0_UNIT = 13;
    private static final int SHADOW_TEX_1_UNIT = 14;
    private static final int SHADOW_COLOR_0_UNIT = 12;
    /** High texture unit used transiently for depth-copy binds so no sampler or vanilla-tracked unit is disturbed. */
    private static final int DEPTH_COPY_SCRATCH_UNIT = 11;
    /** Draw-buffer mask for fixed-function content with no pack program: plain color into colortex0 only. */
    private static final int[] FIXED_FUNCTION_MASK = {0};

    /** Sampler name → texture unit, covering both the modern names and the OptiFine legacy aliases. */
    private static final Map<String, Integer> SAMPLER_UNITS = new LinkedHashMap<>();

    static {
        String[] legacyColor = {"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"};
        for (int i = 0; i < IrisRenderTargets.MAX_COLOR_BUFFERS; i++) {
            SAMPLER_UNITS.put("colortex" + i, i);
            SAMPLER_UNITS.put(legacyColor[i], i);
        }
        SAMPLER_UNITS.put("depthtex0", DEPTH_TEX_0_UNIT);
        SAMPLER_UNITS.put("gdepthtex", DEPTH_TEX_0_UNIT);
        SAMPLER_UNITS.put("depthtex1", DEPTH_TEX_1_UNIT);
        SAMPLER_UNITS.put("depthtex2", DEPTH_TEX_2_UNIT);
        // Shadow samplers are parked on unused units so a shadow-reading pack samples nothing instead of colortex0
        // (sampler uniforms default to unit 0). The shadow pass itself is a later phase.
        SAMPLER_UNITS.put("shadowcolor1", 11);
        SAMPLER_UNITS.put("shadowcolor0", 12);
        SAMPLER_UNITS.put("shadowcolor", 12);
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

        FullscreenPass(String name, IrisProgram program, ProgramUniforms uniforms, IrisFramebuffer framebuffer,
                       int[] colorSamplers) {
            this.name = name;
            this.program = program;
            this.uniforms = uniforms;
            this.framebuffer = framebuffer;
            this.colorSamplers = colorSamplers;
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
    private final IrisFramebuffer gbufferFramebuffer;
    /**
     * The gbuffer FBO used from the translucent stage on. When deferred passes flip {@code colortex0}, the world must
     * continue rendering into the new front texture; without deferred passes this is {@link #gbufferFramebuffer}.
     */
    private final IrisFramebuffer translucentGbufferFramebuffer;
    /** The pack's fixed-function gbuffer programs (sky/entities/particles/weather/clouds/hand), phase-switched. */
    private final GbufferPrograms gbufferPrograms;
    /** Every color index attached to the gbuffer FBOs: the union of all gbuffer-stage DRAWBUFFERS masks, sorted. */
    private final int[] gbufferAttachments;
    /** The gbuffer FBO the world is currently rendering into (switches after the deferred chain runs). */
    private IrisFramebuffer currentGbuffer;
    /** The shadow-map pass, or {@code null} when the pack declares no {@code shadow} program. */
    private final IrisShadowRenderer shadowRenderer;
    private final List<FullscreenPass> deferredPasses = new ArrayList<>();
    private final List<FullscreenPass> passes = new ArrayList<>();
    /** Reads the post-composite {@code colortex0}; used to blit to screen when the pack has no working {@code final}. */
    private final IrisFramebuffer blitSourceFramebuffer;

    private boolean worldRenderingActive;
    private boolean destroyed;
    /** One-shot debug dump of the render targets, ~4s after the pipeline builds (0 = fired). */
    private int debugDumpCountdown = 240;

    public IrisRenderingPipeline(ShaderPack pack) {
        Minecraft mc = Minecraft.getMinecraft();
        this.renderTargets = new IrisRenderTargets(mc.displayWidth, mc.displayHeight);

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

            this.gbufferPrograms = new GbufferPrograms(pack, SAMPLER_UNITS);
            this.gbufferAttachments = computeGbufferAttachments(pack, terrainDrawBuffers(pack));
            this.shadowRenderer = createShadowRenderer(pack);
            BufferFlipper flipper = this.renderTargets.getBufferFlipper();

            this.gbufferFramebuffer = createGbufferFramebuffer(flipper);

            for (int i = 0; i < ProgramArrayId.Deferred.getNumPrograms(); i++) {
                Optional<ProgramSource> source = pack.getProgramSet().get(ProgramArrayId.Deferred, i);
                if (!source.isPresent()) {
                    continue;
                }
                FullscreenPass pass = buildCompositePass(source.get(), flipper);
                if (pass != null) {
                    this.deferredPasses.add(pass);
                }
            }
            this.translucentGbufferFramebuffer = this.deferredPasses.isEmpty()
                    ? this.gbufferFramebuffer
                    : createGbufferFramebuffer(flipper);

            for (int i = 0; i < ProgramArrayId.Composite.getNumPrograms(); i++) {
                Optional<ProgramSource> source = pack.getProgramSet().get(ProgramArrayId.Composite, i);
                if (!source.isPresent()) {
                    continue;
                }
                FullscreenPass pass = buildCompositePass(source.get(), flipper);
                if (pass != null) {
                    this.passes.add(pass);
                }
            }

            FullscreenPass finalPass = buildFinalPass(pack, flipper);
            if (finalPass != null) {
                this.passes.add(finalPass);
                this.blitSourceFramebuffer = null;
            } else {
                // No (working) final program: show the post-composite colortex0 by blitting it to the screen.
                IrisFramebuffer blitSource = new IrisFramebuffer();
                blitSource.addColorAttachment(0, frontTexture(flipper, 0));
                blitSource.readBuffer(0);
                this.blitSourceFramebuffer = blitSource;
            }

            LOGGER.info("[Iris] Rendering pipeline ready: {} deferred + {} composite/final pass(es){}, gbuffer {}x{}",
                    this.deferredPasses.size(), this.passes.size(),
                    this.blitSourceFramebuffer != null ? " + colortex0 blit" : "",
                    this.renderTargets.getWidth(), this.renderTargets.getHeight());
            initialized = true;
        } finally {
            if (!initialized) {
                destroy();
            }
        }
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
        Pattern directive = Pattern.compile("const\\s+int\\s+(\\w+?)Format\\s*=\\s*(\\w+)\\s*;");
        for (ProgramSource source : sources) {
            String[] stages = {source.getFragmentSource().orElse(null), source.getVertexSource().orElse(null)};
            for (String stage : stages) {
                if (stage == null) {
                    continue;
                }
                Matcher matcher = directive.matcher(stage);
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
            }
        }
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
        Optional<ProgramSource> shadowSource = pack.getProgramSet().get(ProgramId.Shadow);
        if (!shadowSource.isPresent()) {
            LOGGER.info("[Iris] Pack declares no shadow program; shadow mapping disabled (always-lit stub in use)");
            return null;
        }
        StringBuilder allSources = new StringBuilder();
        for (ProgramId id : new ProgramId[]{ProgramId.Shadow, ProgramId.Terrain, ProgramId.Water, ProgramId.Final}) {
            pack.getProgramSet().get(id).ifPresent(source -> {
                source.getVertexSource().ifPresent(allSources::append);
                source.getFragmentSource().ifPresent(allSources::append);
            });
        }
        String text = allSources.toString();
        int resolution = parseConstInt(text, "shadowMapResolution", 1024);
        float distance = parseConstFloat(text, "shadowDistance", 120.0f);
        try {
            return new IrisShadowRenderer(resolution, distance);
        } catch (Exception e) {
            LOGGER.error("[Iris] Failed to create the shadow renderer; shadows disabled", e);
            return null;
        }
    }

    private static int parseConstInt(String text, String name, int fallback) {
        Matcher matcher = Pattern.compile("const\\s+int\\s+" + name + "\\s*=\\s*(\\d+)").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private static float parseConstFloat(String text, String name, float fallback) {
        Matcher matcher = Pattern.compile("const\\s+float\\s+" + name + "\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(text);
        return matcher.find() ? Float.parseFloat(matcher.group(1)) : fallback;
    }

    /** The color buffers the terrain program writes, per its {@code DRAWBUFFERS} directive. */
    private int[] terrainDrawBuffers(ShaderPack pack) {
        int[] drawBuffers = pack.getProgramSet().get(ProgramId.Terrain)
                .flatMap(ProgramSource::getFragmentSource)
                .map(DrawBuffers::parse)
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
                .map(DrawBuffers::parse)
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
        return result;
    }

    /**
     * A gbuffer FBO world rendering is redirected into: every gbuffer-stage color target attached (writing the current
     * "front" side of each under the given flip state) plus the shared depth texture. The draw-buffer mask starts as
     * plain-color-only; {@link #setPhase} and the terrain override switch it per program, OptiFine-style.
     */
    private IrisFramebuffer createGbufferFramebuffer(BufferFlipper flipper) {
        IrisFramebuffer framebuffer = new IrisFramebuffer();
        for (int index : this.gbufferAttachments) {
            framebuffer.addColorAttachment(index, frontTexture(flipper, index));
        }
        framebuffer.addDepthAttachment(this.renderTargets.getDepthTexture().getTextureId());
        framebuffer.drawBuffers(FIXED_FUNCTION_MASK);
        return framebuffer;
    }

    private FullscreenPass buildCompositePass(ProgramSource source, BufferFlipper flipper) {
        String name = source.getName();
        try {
            IrisProgram program = compileFullscreenProgram(source);
            if (program == null) {
                return null;
            }
            int[] drawBuffers = sanitizeDrawBuffers(name, program.getDrawBuffers());

            // Reads see the current "front" side; the FBO writes the back side; then the written buffers flip.
            int[] colorSamplers = snapshotFrontTextures(flipper);
            IrisFramebuffer framebuffer = this.renderTargets.createColorFramebuffer(drawBuffers);
            for (int buffer : drawBuffers) {
                flipper.flip(buffer);
            }

            ProgramUniforms uniforms = buildUniforms(name, program);
            LOGGER.info("[Iris] Built fullscreen pass '{}' writing {}", name, java.util.Arrays.toString(drawBuffers));
            return new FullscreenPass(name, program, uniforms, framebuffer, colorSamplers);
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
            IrisProgram program = compileFullscreenProgram(source.get());
            if (program == null) {
                return null;
            }
            ProgramUniforms uniforms = buildUniforms(name, program);
            LOGGER.info("[Iris] Built final pass");
            return new FullscreenPass(name, program, uniforms, null, snapshotFrontTextures(flipper));
        } catch (Exception e) {
            LOGGER.error("[Iris] Failed to build final pass; falling back to colortex0 blit: {}", e.getMessage());
            return null;
        }
    }

    private IrisProgram compileFullscreenProgram(ProgramSource source) {
        String vshRaw = source.getVertexSource().orElse(null);
        String fshRaw = source.getFragmentSource().orElse(null);
        if (vshRaw == null || fshRaw == null) {
            LOGGER.warn("[Iris] Program '{}' is missing a vertex or fragment stage; skipping", source.getName());
            return null;
        }

        GlShader vertex = null;
        GlShader fragment = null;
        try {
            String vsh = FullscreenTransformer.transformVertexShader(vshRaw);
            String fsh = FullscreenTransformer.transformFragmentShader(fshRaw);
            IrisDebugDump.dumpText("src_" + source.getName() + ".vsh", vsh);
            IrisDebugDump.dumpText("src_" + source.getName() + ".fsh", fsh);
            vertex = new GlShader(ShaderType.VERTEX, source.getName() + ".vsh", vsh);
            fragment = new GlShader(ShaderType.FRAGMENT, source.getName() + ".fsh", fsh);

            GlProgram program = ProgramBuilder.begin(source.getName())
                    .attach(vertex)
                    .attach(fragment)
                    .bindAttributeLocation(FullscreenQuadRenderer.POSITION_SLOT, "a_Position")
                    .bindAttributeLocation(FullscreenQuadRenderer.TEXCOORD_SLOT, "a_TexCoord")
                    .bindFragmentDataLocation(0, "iris_FragData")
                    .link();

            assignSamplerUnits(program);
            return new IrisProgram(program, DrawBuffers.parse(fshRaw));
        } finally {
            if (vertex != null) {
                vertex.destroy();
            }
            if (fragment != null) {
                fragment.destroy();
            }
        }
    }

    /** Points every sampler uniform the program declares at its fixed texture unit (OptiFine's setProgramUniform1i). */
    private static void assignSamplerUnits(GlProgram program) {
        program.bind();
        assignSamplerUnitsToBoundProgram(program.getGlId());
        program.unbind();
    }

    /**
     * Assigns the standard sampler-unit mapping on the <em>currently bound</em> program (raw GL id). Used by the
     * Embeddium terrain override, whose program object is Embeddium's rather than ours.
     */
    public static void assignSamplerUnitsToBoundProgram(int programId) {
        for (Map.Entry<String, Integer> entry : SAMPLER_UNITS.entrySet()) {
            int location = LWJGL.glGetUniformLocation(programId, entry.getKey());
            if (location != -1) {
                LWJGL.glUniform1i(location, entry.getValue());
            }
        }
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

    public static int[] sanitizeDrawBuffers(String name, int[] drawBuffers) {
        List<Integer> valid = new ArrayList<>();
        for (int buffer : drawBuffers) {
            if (buffer >= 0 && buffer < IrisRenderTargets.MAX_COLOR_BUFFERS) {
                valid.add(buffer);
            } else {
                LOGGER.warn("[Iris] '{}' declares unsupported draw buffer {} (only colortex0..7 exist); ignoring it",
                        name, buffer);
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

        // noisetex (unit 15) and the stub shadow maps (units 13/14) ride along for the whole frame (gbuffer +
        // fullscreen stages); vanilla never binds units above 1, and GlStateManager's 8-slot cache can't address them.
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + NOISE_TEX_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.noiseTexture.getTextureId());
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_TEX_0_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.stubShadowMap.getTextureId());
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_TEX_1_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.stubShadowMap.getTextureId());
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0);

        // Default PBR maps on the gbuffer-stage normals/specular units (2/3, so through GlStateManager to keep its
        // cache coherent). The composite stage overwrites these units with colortex2/3 when it runs.
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + 2);
        GlStateManager.bindTexture(this.defaultNormals.getTextureId());
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + 3);
        GlStateManager.bindTexture(this.defaultSpecular.getTextureId());
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);

        this.currentGbuffer = this.gbufferFramebuffer;
        this.gbufferFramebuffer.bind();
        // Clear every attached color target to transparent black once (OptiFine's clearRenderBuffers); vanilla's own
        // fog-colored clear inside renderWorldPass then covers colortex0 + depth. GlStateManager.clearColor keeps the
        // vanilla clear-color cache coherent so updateFogColor's subsequent set is not skipped.
        this.gbufferFramebuffer.drawBuffers(this.gbufferAttachments);
        GlStateManager.clearColor(0.0f, 0.0f, 0.0f, 0.0f);
        LWJGL.glClear(GL11.GL_COLOR_BUFFER_BIT);
        this.gbufferFramebuffer.drawBuffers(FIXED_FUNCTION_MASK);
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
            this.currentGbuffer.drawBuffers(FIXED_FUNCTION_MASK);
        } else {
            entry.getProgram().bind();
            entry.getUniforms().update();
            this.currentGbuffer.drawBuffers(entry.getDrawBuffers());
        }
    }

    /**
     * Called when the Embeddium terrain override program binds ({@code IrisTerrainShaderInterface.setupState}): points
     * the gbuffer's draw-buffer mask at the terrain/water program's {@code DRAWBUFFERS} directive.
     */
    public void onTerrainDraw(int[] drawBuffers) {
        if (!this.worldRenderingActive) {
            return;
        }
        this.currentGbuffer.drawBuffers(drawBuffers);
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

        this.currentGbuffer.bind();
        LWJGL.glViewport(0, 0, this.renderTargets.getWidth(), this.renderTargets.getHeight());

        for (int unit : new int[]{SHADOW_TEX_0_UNIT, SHADOW_TEX_1_UNIT}) {
            LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowRenderer.getDepthTextureId());
        }
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_COLOR_0_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowRenderer.getColorTextureId());
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

        if (this.deferredPasses.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();

        GlStateManager.disableBlend();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableAlpha();

        bindDepthSamplers();
        for (FullscreenPass pass : this.deferredPasses) {
            runPass(pass, mc);
        }

        LWJGL.glUseProgram(0);
        restoreTextureUnits();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();

        // The rest of the world (translucents, hand) renders into the post-deferred front textures.
        this.currentGbuffer = this.translucentGbufferFramebuffer;
        this.translucentGbufferFramebuffer.bind();
        LWJGL.glViewport(0, 0, this.renderTargets.getWidth(), this.renderTargets.getHeight());
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
        Minecraft mc = Minecraft.getMinecraft();

        // Full-screen passes draw with depth/blend/alpha-test off. Going through GlStateManager keeps its state cache
        // coherent with reality, so vanilla's later enable/disable calls are not silently skipped.
        GlStateManager.disableBlend();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableAlpha();

        bindDepthSamplers();

        boolean dumpThisFrame = this.debugDumpCountdown > 0 && --this.debugDumpCountdown == 0;
        if (dumpThisFrame) {
            int width = this.renderTargets.getWidth();
            int height = this.renderTargets.getHeight();
            IrisDebugDump.dumpColorTexture("colortex0_gbuffer", this.renderTargets.getOrCreate(0).getMainTexture(), width, height);
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

        for (FullscreenPass pass : this.passes) {
            runPass(pass, mc);
        }

        if (dumpThisFrame && !this.passes.isEmpty()) {
            FullscreenPass lastPass = this.passes.get(this.passes.size() - 1);
            int colortex4 = lastPass.colorSamplers[4] != 0
                    ? lastPass.colorSamplers[4] : this.renderTargets.getOrCreate(4).getMainTexture();
            IrisDebugDump.dumpColorTexture("colortex4_postcomposite", colortex4,
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

        // Hand control back to vanilla: its framebuffer bound, no program/VAO, texture units cleaned up.
        LWJGL.glUseProgram(0);
        bindMainRenderTarget(mc);
        restoreTextureUnits();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();

        CapturedRenderingState.INSTANCE.rollOverPreviousFrame();
    }

    public boolean isWorldRenderingActive() {
        return this.worldRenderingActive;
    }

    /** Captures the block-atlas dimensions for the {@code atlasSize}/{@code terrainTextureSize} uniforms. */
    private static void captureAtlasSize(Minecraft mc) {
        net.minecraft.client.renderer.texture.ITextureObject atlas =
                mc.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureMap.LOCATION_BLOCKS_TEXTURE);
        if (atlas == null) {
            return;
        }
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_COPY_SCRATCH_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, atlas.getGlTextureId());
        int width = LWJGL.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = LWJGL.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        CapturedRenderingState.INSTANCE.setAtlasSize(width, height);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
    }

    // ------------------------------------------------------------------ state helpers

    /** Runs one full-screen pass into its framebuffer (or Minecraft's framebuffer for the final pass). */
    private void runPass(FullscreenPass pass, Minecraft mc) {
        if (pass.framebuffer != null) {
            pass.framebuffer.bind();
            LWJGL.glViewport(0, 0, this.renderTargets.getWidth(), this.renderTargets.getHeight());
        } else {
            bindMainRenderTarget(mc);
        }
        bindColorSamplers(pass);
        pass.program.bind();
        pass.uniforms.update();
        this.quadRenderer.draw();
    }

    /**
     * Copies the depth of the currently-bound (gbuffer) framebuffer into {@code destination} — how OptiFine snapshots
     * {@code depthtex1}/{@code depthtex2}. Runs on a scratch texture unit so no vanilla-tracked binding is disturbed.
     */
    private void copyDepthTexture(DepthTexture destination) {
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_COPY_SCRATCH_UNIT);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, destination.getTextureId());
        LWJGL.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0,
                this.renderTargets.getWidth(), this.renderTargets.getHeight());
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
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

    private void bindColorSamplers(FullscreenPass pass) {
        for (int i = IrisRenderTargets.MAX_COLOR_BUFFERS - 1; i >= 0; i--) {
            if (pass.colorSamplers[i] != 0) {
                GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + i);
                GlStateManager.bindTexture(pass.colorSamplers[i]);
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
        for (int unit = DEPTH_TEX_0_UNIT; unit <= DEPTH_TEX_2_UNIT; unit++) {
            LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
        for (int unit : new int[]{SHADOW_COLOR_0_UNIT, SHADOW_TEX_0_UNIT, SHADOW_TEX_1_UNIT, NOISE_TEX_UNIT}) {
            LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
        for (int i = IrisRenderTargets.MAX_COLOR_BUFFERS - 1; i >= 0; i--) {
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + i);
            GlStateManager.bindTexture(0);
        }
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
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
        for (FullscreenPass pass : this.deferredPasses) {
            pass.program.destroy();
        }
        this.deferredPasses.clear();
        for (FullscreenPass pass : this.passes) {
            pass.program.destroy();
        }
        this.passes.clear();
        if (this.blitSourceFramebuffer != null) {
            this.blitSourceFramebuffer.destroy();
        }
        if (this.translucentGbufferFramebuffer != null && this.translucentGbufferFramebuffer != this.gbufferFramebuffer) {
            this.translucentGbufferFramebuffer.destroy();
        }
        if (this.gbufferFramebuffer != null) {
            this.gbufferFramebuffer.destroy();
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
