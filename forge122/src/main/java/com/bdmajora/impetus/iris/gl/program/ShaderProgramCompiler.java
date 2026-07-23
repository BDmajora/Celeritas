package com.bdmajora.impetus.iris.gl.program;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.iris.gl.shader.GlShader;
import com.bdmajora.impetus.iris.gl.shader.ShaderType;
import com.bdmajora.impetus.iris.pipeline.IrisDebugDump;
import com.bdmajora.impetus.iris.pipeline.IrisRenderingPipeline;
import com.bdmajora.impetus.iris.shaderpack.ProgramSource;
import com.bdmajora.impetus.iris.shaderpack.preprocessor.GlslPreprocessor;
import com.bdmajora.impetus.iris.targets.IrisRenderTargets;
import com.bdmajora.impetus.iris.terrain.ModernPackTransformer;
import com.bdmajora.impetus.iris.vertices.IrisVertexAttributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Compiles a parsed {@link ProgramSource} (from Phase 1) into a linked {@link IrisProgram}, applying the shared
 * {@code #define} set and binding the OptiFine vertex-attribute slots. This is the bridge between the shader-pack model
 * and the GL layer — the concrete realisation of the Phase 2 milestone "programs compile".
 * <p>
 * Must be called on the render thread (it issues GL calls). The caller is expected to catch
 * {@link com.bdmajora.impetus.iris.gl.shader.ShaderCompileException} / {@link ProgramCreationException} and disable
 * shaders on failure rather than crash.
 */
public final class ShaderProgramCompiler {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");
    public static final String HAND_LIGHTMAP_UNIFORM = "impetus_HandLightmap";

    private ShaderProgramCompiler() {
    }

    public static IrisProgram compile(String name, ProgramSource source, Map<String, String> defines) {
        String vertexSource = source.getVertexSource().orElse(null);
        String fragmentSource = source.getFragmentSource().orElse(null);
        String geometrySource = source.getGeometrySource().orElse(null);

        if (vertexSource == null || fragmentSource == null) {
            throw new ProgramCreationException("Program '" + name + "' is missing a vertex or fragment stage");
        }

        // Modern packs (#version 130+ single-source dual-stage, e.g. Complementary) need the #version bumped to
        // "330 compatibility" to compile on the 1.12.2 compat context — exactly like the fullscreen/terrain modern
        // paths. Without this these gbuffer programs fail to compile and their phases fall back to vanilla-style
        // rendering; for gbuffers_clouds that means drawing the vanilla cloud plane the pack explicitly discards
        // (gl_Position = vec4(-1.0) + discard when CLOUD_STYLE != 50), which is the "clouds move with the player" bug.
        // GLSL-120 packs (LIGHT) do not need the version transform, but they still share the same dense draw-buffer
        // routing as modern packs.
        int[] drawBuffers = DrawBuffers.sanitize(
                DrawBuffers.parseActive(fragmentSource, defines), IrisRenderTargets.MAX_COLOR_BUFFERS);
        if (ModernPackTransformer.isModernSource(fragmentSource)) {
            vertexSource = ModernPackTransformer.transform(vertexSource);
            fragmentSource = ModernPackTransformer.transform(fragmentSource);
            if (geometrySource != null) {
                geometrySource = ModernPackTransformer.transform(geometrySource);
            }
        }
        vertexSource = neutralizeUnfedVanillaAttributes(vertexSource);
        if (isFirstPersonHandProgram(name)) {
            vertexSource = injectHandLightmapBridge(vertexSource);
        }
        fragmentSource = DrawBuffers.rewriteFragmentOutputs(fragmentSource, drawBuffers);

        String processedVertex = IrisRenderingPipeline.stabilizeShaderSource(name,
                applyDefines(vertexSource, defines));
        String processedFragment = IrisRenderingPipeline.stabilizeShaderSource(name,
                applyDefines(fragmentSource, defines));

        // Dump the driver-visible source for every gbuffer program (entities, hand, block, …). The terrain/fullscreen
        // paths dump their own; these immediate-mode programs were the blind spot when debugging entity/hand artifacts.
        IrisDebugDump.dumpText("src_" + name + ".vsh", processedVertex);
        IrisDebugDump.dumpText("src_" + name + ".fsh", processedFragment);

        GlShader vertexShader = null;
        GlShader fragmentShader = null;
        GlShader geometryShader = null;
        try {
            vertexShader = new GlShader(ShaderType.VERTEX, name + ".vsh", processedVertex);
            fragmentShader = new GlShader(ShaderType.FRAGMENT, name + ".fsh", processedFragment);

            ProgramBuilder builder = ProgramBuilder.begin(name)
                    .attach(vertexShader)
                    .attach(fragmentShader);

            if (geometrySource != null) {
                geometryShader = new GlShader(ShaderType.GEOMETRY, name + ".gsh",
                        IrisRenderingPipeline.stabilizeShaderSource(name,
                                applyDefines(geometrySource, defines)));
                builder.attach(geometryShader);
            }

            bindOptifineAttributes(builder, vertexSource);

            GlProgram program = builder.link();
            LOGGER.info("[Iris] {} resolved DRAWBUFFERS {}", name, Arrays.toString(drawBuffers));
            return new IrisProgram(program, drawBuffers);
        } finally {
            // The stage objects are no longer needed once the program is linked (or if linking failed).
            if (vertexShader != null) {
                vertexShader.destroy();
            }
            if (fragmentShader != null) {
                fragmentShader.destroy();
            }
            if (geometryShader != null) {
                geometryShader.destroy();
            }
        }
    }

    private static void bindOptifineAttributes(ProgramBuilder builder, String vertexSource) {
        // Only bind slots for attributes the vertex shader actually declares, matching OptiFine's setupProgram.
        if (declaresAttribute(vertexSource, IrisVertexAttributes.MC_ENTITY)) {
            builder.bindAttributeLocation(IrisVertexAttributes.MC_ENTITY_SLOT, IrisVertexAttributes.MC_ENTITY);
        }
        if (declaresAttribute(vertexSource, IrisVertexAttributes.MC_MID_TEX_COORD)) {
            builder.bindAttributeLocation(IrisVertexAttributes.MC_MID_TEX_COORD_SLOT, IrisVertexAttributes.MC_MID_TEX_COORD);
        }
        if (declaresAttribute(vertexSource, IrisVertexAttributes.AT_TANGENT)) {
            builder.bindAttributeLocation(IrisVertexAttributes.AT_TANGENT_SLOT, IrisVertexAttributes.AT_TANGENT);
        }
    }

    private static boolean declaresAttribute(String source, String attributeName) {
        // Matches OptiFine's `attribute <type> <name>` scan, tolerant of both GLSL 120 `attribute` and 150 `in`.
        return source.matches("(?s).*\\b(?:attribute|in)\\s+\\w+\\s+" + attributeName + "\\b.*");
    }

    /**
     * The immediate-mode (vanilla-geometry) gbuffer programs compiled here — entities, hand, block-entities, particles,
     * held items — never receive the OptiFine generic vertex attributes the pack declares. OptiFine's own renderer
     * computes and submits {@code at_tangent}/{@code mc_midTexCoord} per vertex (via SVertexBuilder); the vanilla
     * 1.12.2 draw path does not, so those slots read the GL default generic value {@code (0,0,0,1)}. Two distinct
     * failures result in ADVANCED_MATERIALS packs (BSL, Complementary v4 / Insanity), and both must be neutralized:
     *
     * <ul>
     *   <li>{@code at_tangent = (0,0,0,1)} → {@code normalize(at_tangent.xyz)} and
     *       {@code normalize(cross(at_tangent.xyz, gl_Normal))} are {@code normalize(vec3(0))} = <b>NaN</b> on every
     *       vertex, poisoning the fragment TBN matrix. Replaced with an orthonormal basis derived from the vertex
     *       normal; with the neutral normal map (0,0,1) the pack reconstructs {@code newNormal == gl_Normal}, so
     *       shading is correct and finite.</li>
     *   <li>{@code mc_midTexCoord = (0,0,0,1)} → the vertex shader builds the atlas-tiling basis
     *       ({@code vTexCoordAM}/{@code vTexCoord}) from {@code texCoord - midCoord}. With {@code midCoord == 0} that
     *       basis is garbage, and with {@code PARALLAX} enabled {@code GetParallaxCoord} returns out-of-sprite
     *       (negative) coordinates that re-sample the albedo via {@code texture2DGradARB} — the salt-and-pepper
     *       <b>speckle noise</b> smeared over every entity and the hand. Aliased to the vertex's own texcoord so
     *       {@code midCoord == texCoord}: the tile size collapses to zero, parallax becomes a no-op, and the albedo is
     *       sampled at {@code texCoord} exactly. (POM on standalone entity/hand textures is meaningless anyway.)</li>
     * </ul>
     *
     * Only the {@code ShaderProgramCompiler} path (vanilla geometry) is affected; terrain/water get real tangents and
     * sprite centers from the chunk vertex format via {@code ImpetusTerrainTransformer} and are compiled elsewhere.
     */
    private static String neutralizeUnfedVanillaAttributes(String source) {
        source = source.replaceAll("(?m)^\\s*(?:attribute|in)\\s+vec4\\s+at_tangent\\s*;",
                "vec4 iris_tangentFallback() { "
                        + "vec3 n = normalize(gl_Normal); "
                        + "vec3 t = abs(n.y) < 0.99 ? cross(n, vec3(0.0, 1.0, 0.0)) : vec3(1.0, 0.0, 0.0); "
                        + "return vec4(normalize(t), 1.0); }\n"
                        + "#define at_tangent (iris_tangentFallback())");
        source = source.replaceAll("(?m)^\\s*(?:attribute|in)\\s+vec4\\s+mc_midTexCoord\\s*;",
                "#define mc_midTexCoord gl_MultiTexCoord0");
        return source;
    }

    private static boolean isFirstPersonHandProgram(String name) {
        return "gbuffers_hand".equals(name) || "gbuffers_hand_water".equals(name);
    }

    private static String injectHandLightmapBridge(String source) {
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        int insertIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith("#version")) {
                insertIndex = i + 1;
                break;
            }
        }
        if (insertIndex < 0) {
            lines.add(0, "#version " + GlslPreprocessor.DEFAULT_VERSION);
            insertIndex = 1;
        }
        for (int i = insertIndex; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith("#extension")) {
                insertIndex = i + 1;
            }
        }

        lines.add(insertIndex, "uniform vec2 " + HAND_LIGHTMAP_UNIFORM + ";");
        return String.join("\n", lines).replaceAll("\\bgl_MultiTexCoord1\\b",
                "vec4(" + HAND_LIGHTMAP_UNIFORM + ", 0.0, 1.0)");
    }

    private static String applyDefines(String source, Map<String, String> defines) {
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        List<String> processed = GlslPreprocessor.injectDefines(lines, defines);
        return String.join("\n", processed);
    }
}
