package com.bdmajora.impetus.umbra.terrain;

import com.bdmajora.impetus.umbra.gl.program.DrawBuffers;

import java.util.regex.Pattern;

// Rewrites a GLSL-120 composite, deferred or final program to 330 core: injects quad attributes, aliases the gl_* built-ins onto them with an ortho MVP so ftransform() still maps the quad, and promotes fragment outputs
public final class FullscreenTransformer {
    private static final Pattern VERSION = Pattern.compile("^\\s*#version[^\\n]*\\n", Pattern.MULTILINE);

    private FullscreenTransformer() {
    }

    private static final String VERTEX_PROLOGUE = String.join("\n",
            "#version 330 core",
            "in vec4 a_Position;",
            "in vec2 a_TexCoord;",
            "vec4 iris_Vertex;",
            "vec4 iris_MultiTexCoord0;",
            "vec4 iris_MultiTexCoord1 = vec4(0.0, 0.0, 0.0, 1.0);",
            "vec4 iris_MultiTexCoord2 = vec4(0.0, 0.0, 0.0, 1.0);",
            "vec4 iris_MultiTexCoord3 = vec4(0.0, 0.0, 0.0, 1.0);",
            "vec4 iris_VertColor = vec4(1.0);",
            "vec3 iris_VertNormal = vec3(0.0, 0.0, 1.0);",
            "mat4 iris_Identity = mat4(1.0);",
            "mat3 iris_Identity3 = mat3(1.0);",
            // Ortho mapping the [0,1] quad to NDC (column-major, M*(x,y,z,1)=(2x-1,2y-1,0,1)), matching Umbra's composite gl_ProjectionMatrix and the modern path's pushFullscreenFixedFunctionMatrices
            "mat4 iris_FullscreenProj = mat4(vec4(2.0, 0.0, 0.0, 0.0), vec4(0.0, 2.0, 0.0, 0.0), vec4(0.0), vec4(-1.0, -1.0, 0.0, 1.0));",
            "#define gl_Vertex iris_Vertex",
            "#define gl_MultiTexCoord0 iris_MultiTexCoord0",
            "#define gl_MultiTexCoord1 iris_MultiTexCoord1",
            "#define gl_MultiTexCoord2 iris_MultiTexCoord2",
            "#define gl_MultiTexCoord3 iris_MultiTexCoord3",
            "#define gl_Color iris_VertColor",
            "#define gl_Normal iris_VertNormal",
            "#define gl_ModelViewProjectionMatrix iris_FullscreenProj",
            "#define gl_ModelViewMatrix iris_Identity",
            "#define gl_ProjectionMatrix iris_FullscreenProj",
            "#define gl_NormalMatrix iris_Identity3",
            "#define gl_TextureMatrix iris_TextureMatrixArray",
            "mat4 iris_TextureMatrixArray[8] = mat4[8](mat4(1.0),mat4(1.0),mat4(1.0),mat4(1.0),mat4(1.0),mat4(1.0),mat4(1.0),mat4(1.0));",
            "out vec4 iris_TexCoord[4];",
            "#define gl_TexCoord iris_TexCoord",
            "out float iris_FogFragCoord;",
            "#define gl_FogFragCoord iris_FogFragCoord",
            "vec4 iris_shadow2D(sampler2DShadow s, vec3 p) { return vec4(texture(s, p)); }",
            "vec4 iris_shadow2DLod(sampler2DShadow s, vec3 p, float l) { return vec4(textureLod(s, p, l)); }",
            "#define ftransform() (iris_FullscreenProj * iris_Vertex)",
            ""
    ) + "\n";

    // The generated vertex main is APPENDED after the pack body, since the hoisted global initialisers it runs reference pack globals and uniforms that must already be declared
    private static String vertexMain(String hoistedAssignments) {
        return "\nvoid main() {\n"
                + "    iris_Vertex = a_Position;\n"
                + "    iris_MultiTexCoord0 = vec4(a_TexCoord, 0.0, 1.0);\n"
                + hoistedAssignments
                + "    irisMain();\n"
                + "}\n";
    }

    // The fragment output array length is queried from the driver; a fixed 16 demands 16 contiguous locations, exceeding GL_MAX_DRAW_BUFFERS and failing to link on Mesa
    private static String fragmentPrologue() {
        return String.join("\n",
            "#version 330 core",
            "layout(location = 0) out vec4 iris_FragData[" + DrawBuffers.fragmentOutputArraySize() + "];",
            "#define gl_FragColor iris_FragData[0]",
            "#define gl_FragData iris_FragData",
            "in vec4 iris_TexCoord[4];",
            "#define gl_TexCoord iris_TexCoord",
            "in float iris_FogFragCoord;",
            "#define gl_FogFragCoord iris_FogFragCoord",
            // gl_Fog.* stand-ins MUST be real per-frame uniforms fed by CommonUniforms, not constants: on OptiFine 1.12.2 packs like Sildur's read gl_Fog.start/end/color in composite fog, and const 0/1 forces full-strength fog (the "everything above water is blue" haze). Unused ones are stripped
            "uniform vec4 iris_FogColor;",
            "uniform float iris_FogDensity;",
            "uniform float iris_FogStart;",
            "uniform float iris_FogEnd;",
            // gl_Fog.scale is inlined as an expression by FogParameters (Umbra's 1/(end-start)), so deliberately no iris_FogScale declaration here
            "vec4 iris_shadow2D(sampler2DShadow s, vec3 p) { return vec4(texture(s, p)); }",
            "vec4 iris_shadow2DLod(sampler2DShadow s, vec3 p, float l) { return vec4(textureLod(s, p, l)); }",
            ""
        ) + "\n";
    }

    // Injects the quad attributes and ortho aliases, then modernises
    public static String transformVertexShader(String source) {
        String body = strip(source);
        body = renameMain(body);
        body = convertVaryings(body, "out");
        body = dropAttribute(body);
        body = modernize(body);
        // Pack globals initialized from uniforms are undefined under 330 (drivers may evaluate before upload, giving zeros/NaNs); run those initializers at the top of the generated main like GLSL 120 did
        GlslGlobalInitHoister.Result hoist = GlslGlobalInitHoister.hoist(body);
        return VERTEX_PROLOGUE + hoist.body + vertexMain(hoist.hoistedAssignments);
    }

    // Fragment epilogue wrapping the pack's main and scrubbing non-finite components from every written slot; a NaN survives the chain, poisons TAA history and turns one divide-by-zero into a permanent smear
    private static String fragmentEpilogue(int[] drawBuffers) {
        StringBuilder out = new StringBuilder("\nvoid main() {\n    irisMain();\n");
        int slots = drawBuffers == null ? 1 : Math.max(1, drawBuffers.length);
        for (int slot = 0; slot < slots; slot++) {
            String target = "iris_FragData[" + slot + "]";
            // Per-component so a NaN in one channel does not discard the other three; two mixes since `||` is scalar in GLSL and there is no component-wise or() for bvec4
            out.append("    ").append(target).append(" = mix(").append(target)
                    .append(", vec4(0.0), isnan(").append(target).append("));\n");
            out.append("    ").append(target).append(" = mix(").append(target)
                    .append(", vec4(0.0), isinf(").append(target).append("));\n");
        }
        return out.append("}\n").toString();
    }

    // Fragment rewrite with default draw buffers
    public static String transformFragmentShader(String source) {
        return transformFragmentShader(source, DrawBuffers.DEFAULT);
    }

    // Fragment rewrite routing gl_FragData to the given targets
    public static String transformFragmentShader(String source, int[] drawBuffers) {
        String body = strip(source);
        body = renameMain(body);
        body = convertVaryings(body, "in");
        body = modernize(body);
        body = DrawBuffers.rewriteFragmentOutputs(body, drawBuffers);
        GlslGlobalInitHoister.Result hoist = GlslGlobalInitHoister.hoist(body);
        String transformed = fragmentPrologue() + hoist.body
                + fragmentEpilogue(drawBuffers).replace("    irisMain();", hoist.hoistedAssignments + "    irisMain();");
        return transformed;
    }

    // Removes #version and the pack's own attribute lines
    private static String strip(String source) {
        return VERSION.matcher(source).replaceFirst("");
    }

    // Renames EVERY void main(), not just the first: a flattened source holds several in mutually exclusive #ifdef branches, and any left named main collides with the generated one
    private static String renameMain(String source) {
        return source.replaceAll("\\bvoid\\s+main\\s*\\(\\s*(void)?\\s*\\)", "void irisMain()");
    }

    // varying becomes out or in KEEPING any qualifier in front (flat, centroid, invariant); anchoring at ^\s*varying skips `flat varying`, and a surviving `varying` at 330 core is a hard error (see ImpetusTerrainTransformer.convertVaryings)
    private static String convertVaryings(String source, String direction) {
        return source.replaceAll(
                "(?m)^(\\s*)((?:(?:invariant|flat|smooth|noperspective|centroid)\\s+)*)varying\\b",
                "$1$2" + direction);
    }

    // Removes attribute declarations the transform supplies itself
    private static String dropAttribute(String source) {
        return source.replaceAll("(?m)^(\\s*)attribute\\s+", "$1");
    }

    // varying to in/out, gl_FragColor to a declared output, and the rest of the 120 to 330 delta
    private static String modernize(String source) {
        source = rewriteLegacyProjectionProducts(source);
        source = rewriteFogParameters(source);
        // A sampler literally named "texture" clashes with the 330 builtin, so rename it first (the word boundary keeps texture2D/texture2DLod); the unit table maps "gtexture" to the same unit
        source = source.replaceAll("\\btexture\\b", "gtexture");
        source = source.replaceAll("\\btexture2DLod\\b", "textureLod");
        source = source.replaceAll("\\btexture2D\\b", "texture");
        source = source.replaceAll("\\btexture3D\\b", "texture");
        // shadow2D must keep returning vec4 (packs swizzle .x/.z off it); the iris_ wrappers are in the prologues.
        source = source.replaceAll("\\bshadow2DLod\\b", "iris_shadow2DLod");
        source = source.replaceAll("\\bshadow2D\\b", "iris_shadow2D");
        return source;
    }

    // Some GLSL 120 packs project as vec4(dir, 1.0) * gbufferProjection (row-vector form), which with column-major matrices reads the projection transposed and sends Sildur's godray source off screen; rewritten to column-vector form, while model-view and inverse maths are left alone since those are often row-vector on purpose
    private static String rewriteLegacyProjectionProducts(String source) {
        return source.replaceAll(
                "\\bvec4\\s*\\(([^;\\n]+)\\)\\s*\\*\\s*\\b(gbufferProjection|gbufferPreviousProjection|shadowProjection)\\b",
                "$2 * vec4($1)");
    }

    // gl_Fog.* onto the pipeline's fog uniforms
    private static String rewriteFogParameters(String source) {
        return FogParameters.rewrite(source);
    }
}
