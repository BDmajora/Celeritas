package com.bdmajora.impetus.iris.terrain;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.engine.impl.gl.shader.GlProgram;
import com.bdmajora.impetus.engine.impl.gl.shader.GlShader;
import com.bdmajora.impetus.engine.impl.gl.shader.ShaderType;
import com.bdmajora.impetus.engine.impl.render.chunk.shader.ChunkShaderInterface;
import com.bdmajora.impetus.engine.impl.render.chunk.shader.ChunkShaderOptions;
import com.bdmajora.impetus.iris.Iris;
import com.bdmajora.impetus.iris.gl.blending.ProgramAlphaTest;
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
        // One program per chunk pass, the way OptiFine (programs 12/9/8) and Iris (TERRAIN_TRANSLUCENT/TERRAIN_CUTOUT/
        // TERRAIN_SOLID) both split them. The translucent (reverse-ordered) pass is gbuffers_water; the solid pass is
        // the one that needs no fragment discard, which is precisely the distinction gbuffers_terrain_solid exists to
        // let a pack compile away. All three fall back to gbuffers_terrain for packs that ship only that, and
        // TerrainCutoutMip additionally falls back to TerrainCutout, so both pack conventions resolve. (Impetus draws
        // cutout and cutout-mipped as one pass by default; with pass consolidation off the separate cutout pass also
        // lands on TerrainCutoutMip, which only differs for a pack shipping both cutout programs with distinct code.)
        ProgramId programId;
        if (options.pass().isReverseOrder()) {
            programId = ProgramId.Water;
        } else if (options.pass().supportsFragmentDiscard()) {
            programId = ProgramId.TerrainCutoutMip;
        } else {
            programId = ProgramId.TerrainSolid;
        }
        return build(pack, options, programId);
    }

    /**
     * The pack's shadow terrain program for the shadow-map pass, or {@code null} (nothing drawn) if it won't build.
     * <p>
     * Split by chunk pass exactly as {@link #getProgramOverride} splits the camera pass, because Iris and OptiFine
     * both split the shadow pass the same way: Iris has {@code ShadowWater}/{@code ShadowCutout}/{@code ShadowSolid}
     * in its shadow ProgramGroup, and OptiFine ships {@code shadow_solid}/{@code shadow_cutout} at program indices
     * 31/32. A pack declaring {@code shadow_solid} is telling the compiler it can drop the alpha test for the solid
     * pass; previously that file was loaded and then never asked for.
     * <p>
     * Every one of these falls back to plain {@code shadow}, so a pack shipping only {@code shadow} resolves to the
     * identical source it did before and nothing about its shadow map changes.
     */
    public static GlProgram<ChunkShaderInterface> getShadowProgramOverride(ChunkShaderOptions options) {
        ShaderPack pack = Iris.getCurrentPack();
        if (pack == null) {
            return null;
        }
        if (SHADOW_PROGRAMS.containsKey(options)) {
            return SHADOW_PROGRAMS.get(options);
        }
        ProgramId programId;
        if (options.pass().isReverseOrder()) {
            programId = ProgramId.ShadowWater;
        } else if (options.pass().supportsFragmentDiscard()) {
            programId = ProgramId.ShadowCutout;
        } else {
            programId = ProgramId.ShadowSolid;
        }
        GlProgram<ChunkShaderInterface> program = build(pack, options, programId);
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
            if (!pack.getProperties().getProgramEnabled(source.getName()).orElse(Boolean.TRUE)) {
                LOGGER.info("[Iris] Skipping disabled terrain program '{}'", source.getName());
                return null;
            }
            String vshSource = source.getVertexSource().orElse(null);
            String fshSource = source.getFragmentSource().orElse(null);
            if (vshSource == null || fshSource == null) {
                return null;
            }
            // Raw texture.gbuffers.<sampler> directives: type-checked rename to the minted customtexN sampler.
            vshSource = com.bdmajora.impetus.iris.shaderpack.texture.CustomTextureTransformer.transform(
                    source.getName(), vshSource,
                    com.bdmajora.impetus.iris.shaderpack.texture.TextureStage.GBUFFERS_AND_SHADOW);
            fshSource = com.bdmajora.impetus.iris.shaderpack.texture.CustomTextureTransformer.transform(
                    source.getName(), fshSource,
                    com.bdmajora.impetus.iris.shaderpack.texture.TextureStage.GBUFFERS_AND_SHADOW);
            // Modern (1.17+) attribute/matrix names -> fixed-function built-ins.
            vshSource = VanillaNameTransformer.transform(vshSource);
            fshSource = VanillaNameTransformer.transform(fshSource);

            // Modern (#version 130+) dual-stage packs (Complementary) use the compatibility stage normalizer; the
            // GLSL-120 Chocapic family (LIGHT) keeps the full rewrite.
            boolean modern = ModernPackTransformer.isModernSource(fshSource);
            // Scoped per program: a program listed in `impetus.iris.legacyPrograms` compiles without IS_IRIS. The same
            // map feeds parseActive below and injectDefines further down, so the DRAWBUFFERS layout and the branch the
            // shader actually compiles always agree (mismatching them is what corrupted colortex1 on water pixels).
            Map<String, String> macros = com.bdmajora.impetus.iris.gl.shader.ShaderMacros.forProgram(
                    pack.getEnvironmentDefines(), programId.getSourceName());
            int[] drawBuffers = IrisRenderingPipeline.sanitizeDrawBuffers(
                    programId.getSourceName(), DrawBuffers.parseActive(fshSource, macros));
            // The GLSL-120 terrain path (Chocapic family: Sildur's, BSL, ...) needs the same
            // MC_*/IS_IRIS/IRIS_VERSION macro environment the 120 gbuffers path and the modern path
            // already get. Without it gbuffers_water compiles its pre-Iris/pre-1.16 branch: it writes
            // gl_FragData[2] and skips the SSR reflection + water-fog blocks (both gated behind
            // `defined(IS_IRIS) || MC_VERSION >= 11604`). Worse, DrawBuffers.parseActive above IS given
            // the macro env (so it lays out DRAWBUFFERS:41), leaving the gl_FragData[2] write pointed at
            // an unbound slot and corrupting colortex1 on water pixels. Injecting the macros after the
            // 330 rewrite makes the shader and the framebuffer layout agree and turns water reflections on.
            //
            // The legacy branch folds at the very END, after injectDefines, because that is the only point where it
            // sees what the driver will see: this branch deliberately injects the macros *after* the 330 rewrite (see
            // above), so folding any earlier would evaluate the pack's conditionals against an empty macro
            // environment. The modern branch reaches the same state through stabilizeShaderSource.
            //
            // Skipping the fold here is what left water unshaded. Pastel is a GLSL-120 pack, so it takes the legacy
            // branch, and its lib/atmospherics/fog.glsl carries `#if (in(biome, BIOME_SOUL_SAND_VALLEY)` — a
            // shaders.properties expression pasted into GLSL with a paren missing. The driver answered
            // `iris_gbuffers_terrain.fsh: 0(1209): error C0105: Syntax error in #if`, plus
            // `0(1210): C1038: declaration of "fogColor" conflicts with previous declaration at 0(1175)` — the second
            // error is the tell that "treat it as false" is the only reading under which this pack compiles at all,
            // since the overworld build already declared fogColor and the malformed branch declares it again. The
            // translucent pass then logged "Failed to build terrain override; using Impetus default", i.e. water was
            // drawn by Impetus's own shader with none of the pack's reflection or sun-glint work.
            String vsh = modern
                    ? ImpetusTerrainTransformer.transformVertexShaderModern(
                            IrisRenderingPipeline.stabilizeShaderSource(programId.getSourceName(),
                                    com.bdmajora.impetus.iris.gl.shader.ShaderMacros.injectDefines(vshSource, macros)))
                    : IrisRenderingPipeline.foldUncompilableConditionals(programId.getSourceName(),
                            com.bdmajora.impetus.iris.gl.shader.ShaderMacros.injectDefines(
                                    ImpetusTerrainTransformer.transformVertexShader(vshSource), macros));
            // Iris SodiumPrograms:77 exactly:
            //   getAlphaTestOverride().orElse(TRANSLUCENT ? NON_ZERO_ALPHA
            //                               : (TERRAIN_CUTOUT || SHADOW_CUTOUT) ? HALF_ALPHA : ALWAYS)
            // The pack's alphaTest.<program> directive wins; otherwise the per-pass default. ALWAYS emits no
            // discard at all, which is why an unspecified solid pass carries none.
            String programName = programId.getSourceName();
            ProgramAlphaTest packAlphaTest = ProgramAlphaTest.from(pack.getProperties(), source.getName());
            String alphaTestSnippet;
            if (packAlphaTest.hasDirectives()) {
                alphaTestSnippet = packAlphaTest.toGlslDiscard("iris_FragData[0].a", "    ");
            } else if (programName.endsWith("_solid")) {
                alphaTestSnippet = "";                                    // AlphaTest.ALWAYS
            } else if (programName.contains("cutout")) {
                alphaTestSnippet = ProgramAlphaTest.glslDiscard(          // AlphaTests.HALF_ALPHA
                        "iris_FragData[0].a", ">", "0.5", "    ");
            } else {
                alphaTestSnippet = ProgramAlphaTest.glslDiscard(          // AlphaTests.NON_ZERO_ALPHA
                        "iris_FragData[0].a", ">", "0.0001", "    ");
            }
            String fsh = modern
                    ? ImpetusTerrainTransformer.transformFragmentShaderModern(
                            IrisRenderingPipeline.stabilizeShaderSource(programId.getSourceName(),
                                    com.bdmajora.impetus.iris.gl.shader.ShaderMacros.injectDefines(fshSource, macros)),
                            drawBuffers, alphaTestSnippet)
                    : IrisRenderingPipeline.foldUncompilableConditionals(programId.getSourceName(),
                            com.bdmajora.impetus.iris.gl.shader.ShaderMacros.injectDefines(
                                    ImpetusTerrainTransformer.transformFragmentShader(fshSource, drawBuffers,
                                            alphaTestSnippet), macros));
            // Name the shader after the program it actually is. This method builds every terrain-family pass — solid,
            // cutout_mipped, translucent (i.e. gbuffers_water) and shadow — and the old hardcoded
            // "iris_gbuffers_terrain" meant a compile failure in the water pass was reported as a gbuffers_terrain
            // error, next to log lines saying gbuffers_terrain had just built successfully. The dump filenames beside
            // this already use getSourceName(); the driver-facing name should agree with them.
            String shaderName = "iris_" + programId.getSourceName();
            // This path builds the ENGINE's GlShader, not iris.gl.shader.GlShader, so it does not inherit the
            // strict-driver rewrites that constructor applies — it has to ask for them. Skipping this is why Mesa kept
            // rejecting `#extension` mid-shader and `texture2D(usampler2D, ...)` in exactly the terrain and shadow
            // programs, long after both fixes were written.
            vsh = com.bdmajora.impetus.iris.shaderpack.preprocessor.GlslPreprocessor
                    .finalizeForDriver(shaderName + ".vsh", vsh);
            fsh = com.bdmajora.impetus.iris.shaderpack.preprocessor.GlslPreprocessor
                    .finalizeForDriver(shaderName + ".fsh", fsh);
            // Dump AFTER finalizing: these dumps are the port's primary diagnostic, and the hoist shifts every line
            // below #version, so a pre-finalize dump disagrees with the line numbers in the driver's error messages.
            com.bdmajora.impetus.iris.pipeline.IrisDebugDump.dumpText(
                    "src_" + programId.getSourceName() + ".vsh", vsh);
            com.bdmajora.impetus.iris.pipeline.IrisDebugDump.dumpText(
                    "src_" + programId.getSourceName() + ".fsh", fsh);
            vertexShader = new GlShader(ShaderType.VERTEX, shaderName + ".vsh", vsh);
            fragmentShader = new GlShader(ShaderType.FRAGMENT, shaderName + ".fsh", fsh);

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
            ProgramAlphaTest alphaTest = packAlphaTest;
            IrisRenderingPipeline.drainGlError();
            GlProgram<ChunkShaderInterface> program =
                    builder.link(context -> new IrisTerrainShaderInterface(context, drawBuffers, blendState, alphaTest));
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
            com.bdmajora.impetus.iris.uniforms.custom.ActiveCustomUniforms.assignTo(uniforms);
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
