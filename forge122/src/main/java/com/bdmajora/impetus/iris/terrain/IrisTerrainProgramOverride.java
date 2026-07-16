package com.bdmajora.impetus.iris.terrain;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.engine.impl.gl.shader.GlProgram;
import com.bdmajora.impetus.engine.impl.gl.shader.GlShader;
import com.bdmajora.impetus.engine.impl.gl.shader.ShaderType;
import com.bdmajora.impetus.engine.impl.render.chunk.shader.ChunkShaderInterface;
import com.bdmajora.impetus.engine.impl.render.chunk.shader.ChunkShaderOptions;
import com.bdmajora.impetus.iris.Iris;
import com.bdmajora.impetus.iris.gl.blending.ProgramBlendState;
import com.bdmajora.impetus.iris.gl.program.DrawBuffers;
import com.bdmajora.impetus.iris.gl.program.ProgramUniforms;
import com.bdmajora.impetus.iris.pipeline.IrisRenderingPipeline;
import com.bdmajora.impetus.iris.shaderpack.ProgramSource;
import com.bdmajora.impetus.iris.shaderpack.ShaderPack;
import com.bdmajora.impetus.iris.shaderpack.loading.ProgramId;
import com.bdmajora.impetus.iris.uniforms.CommonUniforms;
import com.bdmajora.impetus.iris.uniforms.MatrixUniforms;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the shader pack's {@code gbuffers_terrain}/{@code gbuffers_water}, transformed to Impetus's vertex format
 * via {@link ImpetusTerrainTransformer} and wrapped in {@link IrisTerrainShaderInterface}. Iris-native: the pack
 * source comes from Impetus' own {@link ShaderPack}/{@link Iris} model. Returned to {@code MixinShaderChunkRenderer};
 * on any failure returns {@code null} so Impetus's default terrain shader is used and rendering never crashes.
 * <p>
 * Deliberately <em>not</em> cached here: {@code ShaderChunkRenderer} caches the returned program per options in its
 * own map and <b>deletes it</b> when the renderer is torn down (pack switch, reload), so handing out a shared instance
 * would serve a deleted GL program after the first reload. One build per renderer instance is the correct lifecycle.
 */
public final class IrisTerrainProgramOverride {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/IrisTerrain");

    private IrisTerrainProgramOverride() {
    }

    public static boolean areShadersActive() {
        return Iris.isShaderPackInUse();
    }

    /**
     * The pack's {@code shadow} programs, one per options variant. Unlike the gbuffer overrides these are OURS to
     * manage (they are handed out during the shadow pass and never stored in Impetus's per-renderer map), so they
     * are destroyed explicitly when the pipeline goes down. Failed builds cache {@code null} to avoid retry spam.
     */
    private static final Map<ChunkShaderOptions, GlProgram<ChunkShaderInterface>> SHADOW_PROGRAMS = new HashMap<>();

    public static GlProgram<ChunkShaderInterface> getProgramOverride(ChunkShaderOptions options) {
        ShaderPack pack = Iris.getCurrentPack();
        if (pack == null) {
            return null;
        }
        // The translucent chunk pass (the reverse-ordered one) uses gbuffers_water; everything else gbuffers_terrain.
        ProgramId programId = options.pass().isReverseOrder() ? ProgramId.Water : ProgramId.Terrain;
        return build(pack, options, programId);
    }

    /** The pack's {@code shadow} program for the shadow-map pass, or {@code null} (nothing drawn) if it won't build. */
    public static GlProgram<ChunkShaderInterface> getShadowProgramOverride(ChunkShaderOptions options) {
        ShaderPack pack = Iris.getCurrentPack();
        if (pack == null) {
            return null;
        }
        if (SHADOW_PROGRAMS.containsKey(options)) {
            return SHADOW_PROGRAMS.get(options);
        }
        GlProgram<ChunkShaderInterface> program = build(pack, options, ProgramId.Shadow);
        SHADOW_PROGRAMS.put(options, program);
        return program;
    }

    /** Frees the shadow programs. Called from the pipeline teardown on the render thread. */
    public static void destroyShadowPrograms() {
        for (GlProgram<ChunkShaderInterface> program : SHADOW_PROGRAMS.values()) {
            if (program != null) {
                program.delete();
            }
        }
        SHADOW_PROGRAMS.clear();
    }

    private static GlProgram<ChunkShaderInterface> build(ShaderPack pack, ChunkShaderOptions options, ProgramId programId) {
        GlShader vertexShader = null;
        GlShader fragmentShader = null;
        try {
            Optional<ProgramSource> sourceOpt = pack.getProgramSet().get(programId);
            if (!sourceOpt.isPresent()) {
                LOGGER.warn("[Iris] no {} source in pack", programId.getSourceName());
                return null;
            }
            ProgramSource source = sourceOpt.get();
            String vshSource = source.getVertexSource().orElse(null);
            String fshSource = source.getFragmentSource().orElse(null);
            if (vshSource == null || fshSource == null) {
                return null;
            }

            // Modern (#version 130+) dual-stage packs (Complementary) use the compatibility stage normalizer; the
            // GLSL-120 Chocapic family (LIGHT) keeps the full rewrite.
            boolean modern = ModernPackTransformer.isModernSource(fshSource);
            Map<String, String> macros = com.bdmajora.impetus.iris.gl.shader.ShaderMacros.standard();
            int[] drawBuffers = IrisRenderingPipeline.sanitizeDrawBuffers(
                    programId.getSourceName(), DrawBuffers.parseActive(fshSource, macros));
            String vsh = modern
                    ? ImpetusTerrainTransformer.transformVertexShaderModern(
                            IrisRenderingPipeline.stabilizeShaderSource(programId.getSourceName(),
                                    com.bdmajora.impetus.iris.gl.shader.ShaderMacros.injectDefines(vshSource, macros)))
                    : ImpetusTerrainTransformer.transformVertexShader(vshSource);
            String fsh = modern
                    ? ImpetusTerrainTransformer.transformFragmentShaderModern(
                            IrisRenderingPipeline.stabilizeShaderSource(programId.getSourceName(),
                                    com.bdmajora.impetus.iris.gl.shader.ShaderMacros.injectDefines(fshSource, macros)),
                            drawBuffers)
                    : ImpetusTerrainTransformer.transformFragmentShader(fshSource, drawBuffers);
            com.bdmajora.impetus.iris.pipeline.IrisDebugDump.dumpText(
                    "src_" + programId.getSourceName() + ".vsh", vsh);
            com.bdmajora.impetus.iris.pipeline.IrisDebugDump.dumpText(
                    "src_" + programId.getSourceName() + ".fsh", fsh);
            vertexShader = new GlShader(ShaderType.VERTEX, "iris_gbuffers_terrain.vsh", vsh);
            fragmentShader = new GlShader(ShaderType.FRAGMENT, "iris_gbuffers_terrain.fsh", fsh);

            var builder = GlProgram.builder("impetus:iris_terrain");
            builder.attachShader(vertexShader);
            builder.attachShader(fragmentShader);
            int index = 0;
            for (var attribute : options.pass().vertexType().getVertexFormat().getAttributes()) {
                builder.bindAttribute(attribute.getName(), index++);
            }
            LOGGER.info("[Iris] {} resolved DRAWBUFFERS {}", programId.getSourceName(),
                    Arrays.toString(drawBuffers));
            ProgramBlendState blendState = ProgramBlendState.from(pack.getProperties(), source.getName());
            IrisRenderingPipeline.drainGlError();
            GlProgram<ChunkShaderInterface> program =
                    builder.link(context -> new IrisTerrainShaderInterface(context, drawBuffers, blendState));
            IrisRenderingPipeline.reportGlError("terrain '" + programId.getSourceName() + "' link");

            // The pack program needs the full OptiFine uniform set: shaders like LIGHT round-trip positions through
            // gbufferModelView(Inverse), so leaving those at zero collapses every vertex to the origin. Sampler units
            // (shadow, noisetex, …) get the standard mapping; the block/lightmap samplers stay with the interface.
            program.bind();
            IrisRenderingPipeline.assignSamplerUnitsToBoundProgram(program.handle());
            IrisRenderingPipeline.reportGlError("terrain '" + programId.getSourceName() + "' sampler-units");
            program.unbind();
            ProgramUniforms.Builder uniforms = ProgramUniforms.builder(programId.getSourceName(), program.handle());
            CommonUniforms.addCommonUniforms(uniforms);
            MatrixUniforms.addMatrixUniforms(uniforms);
            ((IrisTerrainShaderInterface) program.getInterface()).setUniforms(uniforms.buildUniforms());
            IrisRenderingPipeline.reportGlError("terrain '" + programId.getSourceName() + "' uniforms");

            LOGGER.info("[Iris] Built terrain override program ('{}') for pass '{}'",
                    programId.getSourceName(), options.pass().name());
            return program;
        } catch (Exception e) {
            LOGGER.error("[Iris] Failed to build terrain override; using Impetus default", e);
            return null;
        } finally {
            if (vertexShader != null) {
                vertexShader.delete();
            }
            if (fragmentShader != null) {
                fragmentShader.delete();
            }
        }
    }
}
