package com.bdmajora.impetus.umbra.gl.program;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.umbra.gl.shader.GlShader;
import com.bdmajora.impetus.umbra.gl.shader.ShaderType;
import com.bdmajora.impetus.umbra.pipeline.UmbraDebugDump;
import com.bdmajora.impetus.umbra.pipeline.UmbraRenderingPipeline;
import com.bdmajora.impetus.umbra.shaderpack.ProgramSource;
import com.bdmajora.impetus.umbra.shaderpack.preprocessor.GlslPreprocessor;
import com.bdmajora.impetus.umbra.shaderpack.texture.CustomTextureTransformer;
import com.bdmajora.impetus.umbra.shaderpack.texture.TextureStage;
import com.bdmajora.impetus.umbra.targets.UmbraRenderTargets;
import com.bdmajora.impetus.umbra.terrain.ModernPackTransformer;
import com.bdmajora.impetus.umbra.terrain.VanillaNameTransformer;
import com.bdmajora.impetus.umbra.vertices.UmbraVertexAttributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Compiles a parsed {@link ProgramSource} (from Phase 1) into a linked {@link UmbraProgram}, applying the shared
 * {@code #define} set and binding the OptiFine vertex-attribute slots. This is the bridge between the shader-pack model
 * and the GL layer — the concrete realisation of the Phase 2 milestone "programs compile".
 * <p>
 * Must be called on the render thread (it issues GL calls). The caller is expected to catch
 * {@link com.bdmajora.impetus.umbra.gl.shader.ShaderCompileException} / {@link ProgramCreationException} and disable
 * shaders on failure rather than crash.
 */
public final class ShaderProgramCompiler {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");
    public static final String HAND_LIGHTMAP_UNIFORM = "impetus_HandLightmap";

    private ShaderProgramCompiler() {
    }

    /**
     * The driver-visible source for one gbuffer/shadow program, and the dense draw-buffer routing that goes with it.
     * Split out of {@link #compile} so the headless {@code PackSmoke} tool can exercise the exact same string patching
     * without a GL context — a copy of it there had already drifted, hiding the {@code impetus_HandLightmap}
     * regression from the smoke checks.
     */
    public static final class PatchedSource {
        public final String vertex;
        public final String fragment;
        public final String geometry;
        public final int[] drawBuffers;

        PatchedSource(String vertex, String fragment, String geometry, int[] drawBuffers) {
            this.vertex = vertex;
            this.fragment = fragment;
            this.geometry = geometry;
            this.drawBuffers = drawBuffers;
        }
    }

    public static PatchedSource patchSource(String name, ProgramSource source, Map<String, String> defines) {
        String vertexSource = source.getVertexSource().orElse(null);
        String fragmentSource = source.getFragmentSource().orElse(null);
        String geometrySource = source.getGeometrySource().orElse(null);

        if (vertexSource == null || fragmentSource == null) {
            throw new ProgramCreationException("Program '" + name + "' is missing a vertex or fragment stage");
        }

        // Raw texture.gbuffers.<sampler> directives: redirect the identifier to its minted customtexN name wherever
        // the declared sampler type matches the directive's target (Umbra TextureTransformer).
        vertexSource = CustomTextureTransformer.transform(name, vertexSource, TextureStage.GBUFFERS_AND_SHADOW);
        fragmentSource = CustomTextureTransformer.transform(name, fragmentSource, TextureStage.GBUFFERS_AND_SHADOW);
        geometrySource = CustomTextureTransformer.transform(name, geometrySource, TextureStage.GBUFFERS_AND_SHADOW);

        // Modern packs (#version 130+ single-source dual-stage, e.g. Complementary) need the #version bumped to
        // "330 compatibility" to compile on the 1.12.2 compat context — exactly like the fullscreen/terrain modern
        // paths. Without this these gbuffer programs fail to compile and their phases fall back to vanilla-style
        // rendering; for gbuffers_clouds that means drawing the vanilla cloud plane the pack explicitly discards
        // (gl_Position = vec4(-1.0) + discard when CLOUD_STYLE != 50), which is the "clouds move with the player" bug.
        // GLSL-120 packs (LIGHT) do not need the version transform, but they still share the same dense draw-buffer
        // routing as modern packs.
        int[] drawBuffers = DrawBuffers.sanitize(
                DrawBuffers.parseActive(fragmentSource, defines), UmbraRenderTargets.MAX_COLOR_BUFFERS);
        if (ModernPackTransformer.isModernSource(fragmentSource)) {
            vertexSource = ModernPackTransformer.transform(vertexSource);
            fragmentSource = ModernPackTransformer.transform(fragmentSource);
            if (geometrySource != null) {
                geometrySource = ModernPackTransformer.transform(geometrySource);
            }
        }
        // Modern (1.17+) attribute/matrix names -> fixed-function built-ins. Before the hand bridge, so a hand
        // program written against vaUV2 still ends up going through impetus_HandLightmap.
        vertexSource = VanillaNameTransformer.transform(vertexSource);
        fragmentSource = VanillaNameTransformer.transform(fragmentSource);
        if (geometrySource != null) {
            geometrySource = VanillaNameTransformer.transform(geometrySource);
        }
        vertexSource = neutralizeUnfedVanillaAttributes(vertexSource);
        if (isFirstPersonHandProgram(name)) {
            vertexSource = injectHandLightmapBridge(vertexSource);
        }
        fragmentSource = DrawBuffers.rewriteFragmentOutputs(fragmentSource, drawBuffers);

        return new PatchedSource(
                UmbraRenderingPipeline.stabilizeShaderSource(name, applyDefines(vertexSource, defines)),
                UmbraRenderingPipeline.stabilizeShaderSource(name, applyDefines(fragmentSource, defines)),
                geometrySource == null
                        ? null
                        : UmbraRenderingPipeline.stabilizeShaderSource(name, applyDefines(geometrySource, defines)),
                drawBuffers);
    }

    public static UmbraProgram compile(String name, ProgramSource source, Map<String, String> defines) {
        PatchedSource patched = patchSource(name, source, defines);
        String processedVertex = patched.vertex;
        String processedFragment = patched.fragment;
        String geometrySource = patched.geometry;
        int[] drawBuffers = patched.drawBuffers;

        // Dump the driver-visible source for every gbuffer program (entities, hand, block, …). The terrain/fullscreen
        // paths dump their own; these immediate-mode programs were the blind spot when debugging entity/hand artifacts.
        UmbraDebugDump.dumpText("src_" + name + ".vsh", processedVertex);
        UmbraDebugDump.dumpText("src_" + name + ".fsh", processedFragment);

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
                geometryShader = new GlShader(ShaderType.GEOMETRY, name + ".gsh", geometrySource);
                builder.attach(geometryShader);
            }

            bindOptifineAttributes(builder, processedVertex);

            GlProgram program = builder.link();
            LOGGER.info("[Umbra] {} resolved DRAWBUFFERS {}", name, Arrays.toString(drawBuffers));
            return new UmbraProgram(program, drawBuffers);
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
        if (declaresAttribute(vertexSource, UmbraVertexAttributes.MC_ENTITY)) {
            builder.bindAttributeLocation(UmbraVertexAttributes.MC_ENTITY_SLOT, UmbraVertexAttributes.MC_ENTITY);
        }
        if (declaresAttribute(vertexSource, UmbraVertexAttributes.MC_MID_TEX_COORD)) {
            builder.bindAttributeLocation(UmbraVertexAttributes.MC_MID_TEX_COORD_SLOT, UmbraVertexAttributes.MC_MID_TEX_COORD);
        }
        if (declaresAttribute(vertexSource, UmbraVertexAttributes.AT_TANGENT)) {
            builder.bindAttributeLocation(UmbraVertexAttributes.AT_TANGENT_SLOT, UmbraVertexAttributes.AT_TANGENT);
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
     * Photon declares this attribute as {@code vec2}, while older packs commonly declare {@code vec4}; handle the
     * scalar/vector shapes Umbra's transformer accepts instead of leaving Photon's generic attribute unfed.
     *
     * Only the {@code ShaderProgramCompiler} path (vanilla geometry) is affected; terrain/water get real tangents and
     * real quad texture centres from the chunk vertex format via {@code ImpetusTerrainTransformer} and are compiled
     * elsewhere.
     */
    private static String neutralizeUnfedVanillaAttributes(String source) {
        source = source.replaceAll("(?m)^\\s*(?:attribute|in)\\s+vec4\\s+at_tangent\\s*;",
                "vec4 iris_tangentFallback() { "
                        + "vec3 n = normalize(gl_Normal); "
                        + "vec3 t = abs(n.y) < 0.99 ? cross(n, vec3(0.0, 1.0, 0.0)) : vec3(1.0, 0.0, 0.0); "
                        + "return vec4(normalize(t), 1.0); }\n"
                        + "#define at_tangent (iris_tangentFallback())");
        source = source.replaceAll("(?m)^\\s*(?:attribute|in)\\s+float\\s+mc_midTexCoord\\s*;",
                "#define mc_midTexCoord gl_MultiTexCoord0.x");
        source = source.replaceAll("(?m)^\\s*(?:attribute|in)\\s+vec2\\s+mc_midTexCoord\\s*;",
                "#define mc_midTexCoord gl_MultiTexCoord0.xy");
        source = source.replaceAll("(?m)^\\s*(?:attribute|in)\\s+vec3\\s+mc_midTexCoord\\s*;",
                "#define mc_midTexCoord vec3(gl_MultiTexCoord0.xy, 0.0)");
        source = source.replaceAll("(?m)^\\s*(?:attribute|in)\\s+vec4\\s+mc_midTexCoord\\s*;",
                "#define mc_midTexCoord gl_MultiTexCoord0");
        return source;
    }

    private static boolean isFirstPersonHandProgram(String name) {
        return "gbuffers_hand".equals(name) || "gbuffers_hand_water".equals(name);
    }

    /**
     * Replaces {@code gl_MultiTexCoord1} with a uniform the hand renderer feeds, because vanilla lights held items
     * through GL lighting rather than the lightmap texcoord, so the fixed-function coord arrives at ~0.
     * <p>
     * The declaration goes immediately after the {@code #version} line and any {@code #extension} directives that
     * <em>contiguously</em> follow it. Scanning the whole file for the last {@code #extension} (as this used to) breaks
     * on packs whose flattened include tree contains a guarded {@code #extension} deep inside — Photon's
     * {@code include/global.glsl} has three, so the declaration landed thousands of lines after the first use and
     * {@code gbuffers_hand} failed to compile with "undefined variable {@code impetus_HandLightmap}".
     */
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
        while (insertIndex < lines.size()) {
            String line = lines.get(insertIndex).trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#extension")) {
                insertIndex++;
            } else {
                break;
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
