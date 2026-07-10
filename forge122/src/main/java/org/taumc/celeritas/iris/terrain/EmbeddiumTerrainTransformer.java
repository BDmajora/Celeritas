package org.taumc.celeritas.iris.terrain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforms an OptiFine-style GLSL-120 {@code gbuffers_terrain} program so it can run as Celeritas/Embeddium's terrain
 * shader — i.e. consume Embeddium's <em>uncompressed</em> chunk vertex format ({@code a_PosId}/{@code a_Color}/
 * {@code a_TexCoord}/{@code a_LightCoord}) and matrices ({@code u_ModelViewMatrix}/{@code u_ProjectionMatrix}/
 * {@code u_RegionOffset}) instead of fixed-function state.
 * <p>
 * This is the 1.12.2 analogue of modern Iris's Sodium terrain transformation. The pack cannot read Embeddium's
 * integer-packed vertex attributes at GLSL 120, so the shader is lifted to {@code #version 330 core} and the classic
 * built-ins are remapped: the pack's {@code main} is renamed to {@code irisMain}, and a generated {@code main} decodes
 * the Embeddium vertex into globals that {@code #define}d built-ins ({@code gl_Vertex}, {@code gl_ModelViewMatrix},
 * {@code gl_MultiTexCoord0}, …) point at.
 * <p>
 * <b>Status: iteration 1.</b> It handles the common built-ins/keywords that OptiFine 1.12.2 terrain shaders use; getting
 * a specific pack pixel-correct will need in-game refinement (this is inherently iterative — modern Iris uses a full
 * GLSL parser for the same job). Kept deliberately conservative and well-scoped so each built-in mapping is auditable.
 */
public final class EmbeddiumTerrainTransformer {
    private static final Pattern VERSION = Pattern.compile("^\\s*#version[^\\n]*\\n", Pattern.MULTILINE);

    private EmbeddiumTerrainTransformer() {
    }

    /** Vertex prologue: Embeddium attributes/uniforms + vertex decode + gl_* built-in aliases + main() wrapper. */
    private static final String VERTEX_PROLOGUE = String.join("\n",
            "#version 330 core",
            "// ---- Celeritas/Iris terrain bridge (generated) ----",
            "in vec3 a_PosId;",
            "in vec4 a_Color;",
            "in vec2 a_TexCoord;",
            "in uint a_LightCoord;",
            "in vec4 iris_Normal;",      // true face normal, NormI8 (normalized signed bytes)
            "in vec4 iris_Tangent;",     // at_tangent, w = handedness
            "in vec2 iris_MidTexCoord;", // sprite center in atlas UV
            "in vec2 iris_BlockInfo;",   // (block id, metadata)
            "in vec4 iris_MidBlock;",    // at_midBlock: xyz offset-to-block-center * 64, w block emission
            "uniform mat4 u_ModelViewMatrix;",
            "uniform mat4 u_ProjectionMatrix;",
            "uniform vec3 u_RegionOffset;",
            "",
            "uvec3 _iris_relChunk(uint pos) { return (uvec3(pos) >> uvec3(5u,0u,2u)) & uvec3(7u,3u,7u); }",
            "vec3 _iris_drawTranslation(uint pos) { return vec3(_iris_relChunk(pos)) * 16.0; }",
            "// GLSL-120 shadow2D returned vec4; 330's texture() on a shadow sampler returns float. Wrap so .x/.z work.",
            "vec4 iris_shadow2D(sampler2DShadow s, vec3 p) { return vec4(texture(s, p)); }",
            "vec4 iris_shadow2DLod(sampler2DShadow s, vec3 p, float l) { return vec4(textureLod(s, p, l)); }",
            "",
            "vec4 iris_Vertex;",
            "vec4 iris_Color;",
            "vec4 iris_MultiTexCoord0;",
            "vec4 iris_MultiTexCoord1;",
            "vec4 iris_MultiTexCoord2 = vec4(0.0, 0.0, 0.0, 1.0);",
            "vec4 iris_MultiTexCoord3 = vec4(0.0, 0.0, 0.0, 1.0);",
            "vec4 iris_MidTexFull;",
            "vec4 iris_EntityFull;",
            "// The matrix built-ins alias the uniforms directly (as expressions, not uniform-initialized globals —",
            "// global initializers must be constant expressions in GLSL 330; drivers that accept them may evaluate",
            "// them before uniforms are loaded, collapsing every vertex to the origin).",
            "// gl_TextureMatrix[1] is vanilla's lightmap matrix (scale 1/256, translate 8/256): raw 0..240 lightmap",
            "// coords -> 0..1 UVs. The rest are identity (the block atlas uses untransformed coords).",
            "const mat4 iris_LightmapTextureMatrix = mat4(",
            "    vec4(0.00390625, 0.0, 0.0, 0.0), vec4(0.0, 0.00390625, 0.0, 0.0),",
            "    vec4(0.0, 0.0, 0.00390625, 0.0), vec4(0.03125, 0.03125, 0.03125, 1.0));",
            "mat4 iris_TextureMatrix[8] = mat4[8](mat4(1.0), iris_LightmapTextureMatrix,",
            "    mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0));",
            "",
            "#define gl_Vertex iris_Vertex",
            "#define gl_Color iris_Color",
            "#define gl_MultiTexCoord0 iris_MultiTexCoord0",
            "#define gl_MultiTexCoord1 iris_MultiTexCoord1",
            "#define gl_MultiTexCoord2 iris_MultiTexCoord2",
            "#define gl_MultiTexCoord3 iris_MultiTexCoord3",
            "#define gl_Normal (iris_Normal.xyz)",
            "#define at_tangent iris_Tangent",
            "#define at_midBlock iris_MidBlock",
            "#define mc_midTexCoord iris_MidTexFull",
            "#define mc_Entity iris_EntityFull",
            "#define gl_ModelViewMatrix u_ModelViewMatrix",
            "#define gl_ProjectionMatrix u_ProjectionMatrix",
            "#define gl_ModelViewProjectionMatrix (u_ProjectionMatrix * u_ModelViewMatrix)",
            "#define gl_NormalMatrix (mat3(transpose(inverse(u_ModelViewMatrix))))",
            "#define gl_TextureMatrix iris_TextureMatrix",
            "#define ftransform() (u_ProjectionMatrix * (u_ModelViewMatrix * iris_Vertex))",
            "out float iris_FogFragCoord;",
            "#define gl_FogFragCoord iris_FogFragCoord",
            "out vec4 iris_TexCoordArr[4];",
            "#define gl_TexCoord iris_TexCoordArr",
            "// OptiFine packs rely on fixed-function GL_ALPHA_TEST for cutout transparency, but Embeddium disables it",
            "// and discards in-shader instead; mirror its per-material cutoff (bits 1-2 of the material byte).",
            "const float[4] _IRIS_ALPHA_CUTOFF = float[4](0.0, 0.1, 0.5, 1.0);",
            "flat out float iris_AlphaCutoff;",
            "// ---- end generated prologue ----",
            ""
    ) + "\n";

    /**
     * The generated vertex main is APPENDED after the pack body: the hoisted global initializers it runs reference
     * pack globals/uniforms that must already be declared above it.
     */
    private static String vertexMain(String hoistedAssignments) {
        return "\nvoid main() {\n"
                + "    uint lightData = a_LightCoord;\n" // 'packed' is a reserved word in GLSL 330
                + "    uint drawId = (lightData >> 8u) & 0xFFu;\n"
                + "    vec3 pos = a_PosId + u_RegionOffset + _iris_drawTranslation(drawId);\n"
                + "    iris_Vertex = vec4(pos, 1.0);\n"
                + "    iris_Color = a_Color;\n"
                + "    iris_MultiTexCoord0 = vec4(a_TexCoord, 0.0, 1.0);\n"
                + "    uint blockLight = (lightData >> 16u) & 0xFFu;\n"
                + "    uint skyLight = (lightData >> 24u) & 0xFFu;\n"
                + "    iris_MultiTexCoord1 = vec4(float(blockLight), float(skyLight), 0.0, 1.0);\n"
                + "    iris_MidTexFull = vec4(iris_MidTexCoord, 0.0, 1.0);\n"
                + "    iris_EntityFull = vec4(iris_BlockInfo, 0.0, 1.0);\n"
                + "    iris_AlphaCutoff = _IRIS_ALPHA_CUTOFF[int((lightData >> 1u) & 3u)];\n"
                + hoistedAssignments
                + "    irisMain();\n"
                + "}\n";
    }

    /** Fragment prologue: promote GLSL 120 fragment built-ins to 330 core outputs/keywords. */
    private static final String FRAGMENT_PROLOGUE = String.join("\n",
            "#version 330 core",
            "// ---- Celeritas/Iris terrain bridge (generated) ----",
            "out vec4 iris_FragData[8];",
            "#define gl_FragColor iris_FragData[0]",
            "#define gl_FragData iris_FragData",
            "vec4 iris_shadow2D(sampler2DShadow s, vec3 p) { return vec4(texture(s, p)); }",
            "vec4 iris_shadow2DLod(sampler2DShadow s, vec3 p, float l) { return vec4(textureLod(s, p, l)); }",
            "const mat4 iris_LightmapTextureMatrix = mat4(",
            "    vec4(0.00390625, 0.0, 0.0, 0.0), vec4(0.0, 0.00390625, 0.0, 0.0),",
            "    vec4(0.0, 0.0, 0.00390625, 0.0), vec4(0.03125, 0.03125, 0.03125, 1.0));",
            "mat4 iris_TextureMatrix[8] = mat4[8](mat4(1.0), iris_LightmapTextureMatrix,",
            "    mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0));",
            "#define gl_TextureMatrix iris_TextureMatrix",
            "in float iris_FogFragCoord;",
            "#define gl_FogFragCoord iris_FogFragCoord",
            "in vec4 iris_TexCoordArr[4];",
            "#define gl_TexCoord iris_TexCoordArr",
            "flat in float iris_AlphaCutoff;",
            "// ---- end generated prologue ----",
            ""
    ) + "\n";

    public static String transformVertexShader(String source) {
        String body = stripVersion(source);
        body = renameMain(body);
        body = convertVaryings(body, "out");
        body = dropAttributeStorageQualifier(body);
        body = modernizeCommon(body);
        // Pack globals initialized from uniforms are undefined under 330 (drivers may evaluate them before uniform
        // upload — zeros/NaNs); run those initializers at the top of the generated main, like GLSL 120 did.
        GlslGlobalInitHoister.Result hoist = GlslGlobalInitHoister.hoist(body);
        return VERTEX_PROLOGUE + hoist.body + vertexMain(hoist.hoistedAssignments);
    }

    public static String transformFragmentShader(String source) {
        String body = stripVersion(source);
        body = renameMain(body);
        body = convertVaryings(body, "in");
        body = modernizeCommon(body);
        GlslGlobalInitHoister.Result hoist = GlslGlobalInitHoister.hoist(body);
        return FRAGMENT_PROLOGUE + hoist.body
                + "\nvoid main() {\n" + hoist.hoistedAssignments + "    irisMain();\n"
                + "    if (iris_FragData[0].a < iris_AlphaCutoff) { discard; }\n}\n";
    }

    // ------------------------------------------------------------------ modern (#version 130+) terrain

    /**
     * The same Embeddium vertex bridge, but for modern single-source dual-stage packs (Complementary). We keep the
     * attribute decode, the {@code gl_*}→Embeddium {@code #define}s and the generated {@code main}, but drop every
     * transform that assumes GLSL-120 Chocapic structure: no {@code varying} conversion (the pack flips {@code in}/
     * {@code out} itself with {@code #ifdef VERTEX_SHADER}), no global hoisting, and crucially <b>no</b>
     * {@code texture}→{@code gtexture} rename — modern packs call the {@code texture()} built-in everywhere, so that
     * rename is what corrupted them. The {@code #ifdef VERTEX_SHADER}/{@code FRAGMENT_SHADER} guards and option gates are
     * left for the driver's own preprocessor (compatibility profile). {@code renameMain} still applies: it renames both
     * stages' {@code void main()} to {@code irisMain}, and only the active one survives the driver's {@code #ifdef}.
     */
    public static String transformVertexShaderModern(String source) {
        String body = stripVersion(source);
        body = renameMain(body);
        // Delete the pack's mc_Entity/mc_midTexCoord/at_tangent attribute declarations; the prologue #defines those
        // names onto its own decoded globals, so the pack's declarations would become illegal redeclarations.
        body = dropAttributeStorageQualifier(body);
        return compatFor(VERTEX_PROLOGUE, source) + body + vertexMain("");
    }

    public static String transformFragmentShaderModern(String source) {
        String body = stripVersion(source);
        body = renameMain(body);
        return compatFor(FRAGMENT_PROLOGUE, source) + body
                + "\nvoid main() {\n    irisMain();\n"
                + "    if (iris_FragData[0].a < iris_AlphaCutoff) { discard; }\n}\n";
    }

    /** The shared prologue targets 330 core; modern packs need 330 compatibility (legacy built-ins + modern intrinsics). */
    private static String compat(String prologue) {
        return prologue.replaceFirst("#version 330 core", "#version 330 compatibility");
    }

    /** Image load/store (colored-lighting voxelization) needs 430; plain modern sources keep 330. */
    private static String compatFor(String prologue, String packBody) {
        String version = (packBody.contains("imageStore") || packBody.contains("imageLoad")
                || packBody.contains("imageAtomic")) ? "#version 430 compatibility" : "#version 330 compatibility";
        return prologue.replaceFirst("#version 330 core", version);
    }

    private static String stripVersion(String source) {
        return VERSION.matcher(source).replaceFirst("");
    }

    /**
     * Rename the pack's {@code void main()} to {@code irisMain} so the generated {@code main} can wrap it. Must rename
     * every occurrence: include-flattened sources can contain several {@code main} definitions in mutually exclusive
     * {@code #ifdef} branches, and this rewrite runs before preprocessing.
     */
    private static String renameMain(String source) {
        return source.replaceAll("\\bvoid\\s+main\\s*\\(\\s*(void)?\\s*\\)", "void irisMain()");
    }

    /** {@code varying} → {@code out} (vertex) or {@code in} (fragment). */
    private static String convertVaryings(String source, String direction) {
        return source.replaceAll("(?m)^(\\s*)varying\\b", "$1" + direction);
    }

    /**
     * The pack declares OptiFine's extra attributes ({@code attribute vec2 mc_Entity;} etc.) which we do not yet feed;
     * strip the {@code attribute} storage qualifier so those become plain (default-zero) globals rather than illegal
     * 330-core attribute declarations. (Feeding real mc_Entity/mc_midTexCoord/at_tangent is a later pass.)
     */
    private static String dropAttributeStorageQualifier(String source) {
        // mc_Entity / mc_midTexCoord / at_tangent are now REAL attributes fed by IrisChunkVertexType; the prologue
        // #defines those names onto its own inputs, so the pack's declarations must be deleted outright (the define
        // would otherwise rewrite them into duplicate declarations of the prologue globals).
        source = source.replaceAll("(?m)^\\s*(?:attribute|in)\\s+\\w+\\s+(mc_Entity|mc_midTexCoord|at_tangent|at_midBlock)\\s*;\\s*$", "");
        // Any other attribute becomes an explicitly zero-initialized global — an uninitialized global is undefined.
        source = source.replaceAll("(?m)^(\\s*)attribute\\s+(\\w+)\\s+(\\w+)\\s*;", "$1$2 $3 = $2(0.0);");
        // Fallback for forms the initializer rewrite doesn't cover (e.g. multiple declarators): just drop the keyword.
        return source.replaceAll("(?m)^(\\s*)attribute\\s+", "$1");
    }

    /** Keyword modernizations common to both stages for 330 core. */
    private static String modernizeCommon(String source) {
        // OptiFine's block sampler is often literally named "texture", which clashes with GLSL 330's texture() builtin.
        // Rename the standalone sampler to "gtexture" first (word-boundary avoids touching texture2D/texture2DLod),
        // then modernize the legacy sampling functions to the builtins.
        source = source.replaceAll("\\btexture\\b", "gtexture");
        source = source.replaceAll("\\btexture2DLod\\b", "textureLod");
        source = source.replaceAll("\\btexture3DLod\\b", "textureLod");
        source = source.replaceAll("\\btexture2D\\b", "texture");
        source = source.replaceAll("\\btexture3D\\b", "texture");
        // shadow2D must keep returning vec4 (packs swizzle .x/.z off it); the iris_ wrappers are in the prologues.
        source = source.replaceAll("\\bshadow2DLod\\b", "iris_shadow2DLod");
        source = source.replaceAll("\\bshadow2D\\b", "iris_shadow2D");
        return source;
    }
}
