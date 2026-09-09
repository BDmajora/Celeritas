package com.bdmajora.impetus.umbra.gl.program;

import com.bdmajora.impetus.umbra.gl.shader.GlShader;
import com.bdmajora.impetus.umbra.gl.shader.ShaderType;
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

// Compiles a parsed ProgramSource into a linked UmbraProgram: applies the shared #define set, patches the source,
// binds the OptiFine vertex-attribute slots, links
// The bridge between the shader-pack model and the GL layer, and the compile path for every IMMEDIATE-MODE gbuffer
// program — entities, hand, block entities, particles, held items. Terrain and the fullscreen passes have their own
// transformers and are compiled elsewhere
// Render thread only, since it issues GL calls. Callers are expected to catch ShaderCompileException and
// ProgramCreationException and fall back to vanilla rendering rather than let a bad pack crash the game
public final class ShaderProgramCompiler {
    public static final String HAND_LIGHTMAP_UNIFORM = "impetus_HandLightmap";

    private ShaderProgramCompiler() {
    }

    // The driver-visible source for one gbuffer/shadow program, plus the dense draw-buffer routing that goes with it
    // Kept as its own type, and produced by patchSource rather than inline in compile, so the string patching is one
    // reusable step instead of something a second caller would end up duplicating and letting drift
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

    // The immediate-mode programs compiled here never receive the OptiFine generic vertex attributes a pack
    // declares. OptiFine's own renderer computes and submits at_tangent and mc_midTexCoord per vertex through
    // SVertexBuilder; the vanilla 1.12.2 draw path does not, so those slots read GL's default generic value
    // (0, 0, 0, 1)
    // That produces two distinct failures on ADVANCED_MATERIALS packs (BSL, Complementary v4, Insanity), and both
    // have to be neutralised in the source rather than left to the driver
    //
    // at_tangent = (0,0,0,1) makes normalize(at_tangent.xyz) and normalize(cross(at_tangent.xyz, gl_Normal)) both
    // normalize(vec3(0)), which is NaN on every vertex and poisons the fragment TBN matrix. It is replaced with an
    // orthonormal basis derived from the vertex normal; with the neutral (0,0,1) normal map the pack then
    // reconstructs newNormal == gl_Normal, so shading is both correct and finite
    //
    // mc_midTexCoord = (0,0,0,1) makes the vertex shader build its atlas-tiling basis from texCoord - midCoord,
    // which with midCoord == 0 is garbage. With PARALLAX on, GetParallaxCoord then returns negative out-of-sprite
    // coordinates that re-sample the albedo through texture2DGradARB — the salt-and-pepper speckle smeared over
    // every entity and the hand. It is aliased to the vertex's own texcoord so midCoord == texCoord: the tile size
    // collapses to zero, parallax becomes a no-op, and the albedo is sampled at texCoord exactly. POM on a
    // standalone entity or hand texture is meaningless anyway
    //
    // Both scalar and vector declaration shapes are handled: Photon declares mc_midTexCoord as vec2 while older
    // packs commonly use vec4, and matching only one shape leaves the other pack's attribute unfed
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

    // Replaces gl_MultiTexCoord1 with a uniform the hand renderer feeds
    // Needed because vanilla lights held items through GL lighting rather than through the lightmap texcoord, so
    // the fixed-function coordinate arrives at roughly 0 and the pack renders the hand black
    // The declaration is inserted immediately after the #version line and any #extension directives CONTIGUOUSLY
    // following it. Scanning the whole file for the last #extension, as this used to, breaks on packs whose
    // flattened include tree contains a guarded #extension deep inside — Photon's include/global.glsl has three, so
    // the declaration landed thousands of lines after its first use and gbuffers_hand failed to compile with
    // "undefined variable impetus_HandLightmap"
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
