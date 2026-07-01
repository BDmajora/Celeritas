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
            "uniform mat4 u_ModelViewMatrix;",
            "uniform mat4 u_ProjectionMatrix;",
            "uniform vec3 u_RegionOffset;",
            "",
            "uvec3 _iris_relChunk(uint pos) { return (uvec3(pos) >> uvec3(5u,0u,2u)) & uvec3(7u,3u,7u); }",
            "vec3 _iris_drawTranslation(uint pos) { return vec3(_iris_relChunk(pos)) * 16.0; }",
            "",
            "vec4 iris_Vertex;",
            "vec4 iris_Color;",
            "vec4 iris_MultiTexCoord0;",
            "vec4 iris_MultiTexCoord1;",
            "vec3 iris_Normal;",
            "mat4 iris_ModelView = u_ModelViewMatrix;",
            "mat4 iris_Projection = u_ProjectionMatrix;",
            "mat4 iris_ModelViewProjection = u_ProjectionMatrix * u_ModelViewMatrix;",
            "mat3 iris_NormalMatrix = mat3(transpose(inverse(u_ModelViewMatrix)));",
            "",
            "#define gl_Vertex iris_Vertex",
            "#define gl_Color iris_Color",
            "#define gl_MultiTexCoord0 iris_MultiTexCoord0",
            "#define gl_MultiTexCoord1 iris_MultiTexCoord1",
            "#define gl_Normal iris_Normal",
            "#define gl_ModelViewMatrix iris_ModelView",
            "#define gl_ProjectionMatrix iris_Projection",
            "#define gl_ModelViewProjectionMatrix iris_ModelViewProjection",
            "#define gl_NormalMatrix iris_NormalMatrix",
            "#define ftransform() (iris_ModelViewProjection * iris_Vertex)",
            "",
            "void irisMain();",
            "void main() {",
            "    uint packed = a_LightCoord;",
            "    uint drawId = (packed >> 8u) & 0xFFu;",
            "    vec3 pos = a_PosId + u_RegionOffset + _iris_drawTranslation(drawId);",
            "    iris_Vertex = vec4(pos, 1.0);",
            "    iris_Color = a_Color;",
            "    iris_MultiTexCoord0 = vec4(a_TexCoord, 0.0, 1.0);",
            "    uint blockLight = (packed >> 16u) & 0xFFu;",
            "    uint skyLight = (packed >> 24u) & 0xFFu;",
            "    iris_MultiTexCoord1 = vec4((vec2(blockLight, skyLight) / 15.0) * 240.0, 0.0, 1.0);",
            "    iris_Normal = vec3(0.0, 1.0, 0.0);",
            "    irisMain();",
            "}",
            "// ---- end generated prologue ----",
            ""
    ) + "\n";

    /** Fragment prologue: promote GLSL 120 fragment built-ins to 330 core outputs/keywords. */
    private static final String FRAGMENT_PROLOGUE = String.join("\n",
            "#version 330 core",
            "// ---- Celeritas/Iris terrain bridge (generated) ----",
            "out vec4 iris_FragData[8];",
            "#define gl_FragColor iris_FragData[0]",
            "#define gl_FragData iris_FragData",
            "// ---- end generated prologue ----",
            ""
    ) + "\n";

    public static String transformVertexShader(String source) {
        String body = stripVersion(source);
        body = renameMain(body);
        body = convertVaryings(body, "out");
        body = dropAttributeStorageQualifier(body);
        body = modernizeCommon(body);
        return VERTEX_PROLOGUE + body;
    }

    public static String transformFragmentShader(String source) {
        String body = stripVersion(source);
        body = convertVaryings(body, "in");
        body = modernizeCommon(body);
        return FRAGMENT_PROLOGUE + body;
    }

    private static String stripVersion(String source) {
        return VERSION.matcher(source).replaceFirst("");
    }

    /** Rename the pack's {@code void main()} to {@code irisMain} so the generated {@code main} can wrap it. */
    private static String renameMain(String source) {
        return source.replaceFirst("\\bvoid\\s+main\\s*\\(\\s*(void)?\\s*\\)", "void irisMain()");
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
        return source.replaceAll("(?m)^(\\s*)attribute\\b", "$1//attribute-was-here ");
    }

    /** Keyword modernizations common to both stages for 330 core. */
    private static String modernizeCommon(String source) {
        source = source.replaceAll("\\btexture2D\\b", "texture");
        source = source.replaceAll("\\btexture2DLod\\b", "textureLod");
        source = source.replaceAll("\\btexture3D\\b", "texture");
        source = source.replaceAll("\\bshadow2D\\b", "texture");
        return source;
    }
}
