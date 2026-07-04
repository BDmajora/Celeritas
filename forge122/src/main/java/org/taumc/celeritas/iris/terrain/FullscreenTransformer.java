package org.taumc.celeritas.iris.terrain;

import java.util.regex.Pattern;

/**
 * Transforms a GLSL-120 full-screen shader-pack program ({@code composite}/{@code deferred}/{@code final}) to
 * {@code #version 330 core} for Celeritas. Full-screen passes are trivial in the vertex stage (they just pass the
 * fullscreen quad's position/texcoord through), so this injects an {@code a_Position}/{@code a_TexCoord} attribute
 * pair, aliases the {@code gl_*} built-ins to them (with an identity model-view-projection so {@code ftransform()} /
 * {@code gl_ModelViewProjectionMatrix * gl_Vertex} pass the NDC position straight through), and promotes fragment
 * outputs. The pack's {@code colortexN}/{@code depthtexN} samplers are left as-is (bound by the pipeline).
 */
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
            "#define gl_Vertex iris_Vertex",
            "#define gl_MultiTexCoord0 iris_MultiTexCoord0",
            "#define gl_MultiTexCoord1 iris_MultiTexCoord1",
            "#define gl_MultiTexCoord2 iris_MultiTexCoord2",
            "#define gl_MultiTexCoord3 iris_MultiTexCoord3",
            "#define gl_Color iris_VertColor",
            "#define gl_Normal iris_VertNormal",
            "#define gl_ModelViewProjectionMatrix iris_Identity",
            "#define gl_ModelViewMatrix iris_Identity",
            "#define gl_ProjectionMatrix iris_Identity",
            "#define gl_NormalMatrix iris_Identity3",
            "#define gl_TextureMatrix iris_TextureMatrixArray",
            "mat4 iris_TextureMatrixArray[8] = mat4[8](mat4(1.0),mat4(1.0),mat4(1.0),mat4(1.0),mat4(1.0),mat4(1.0),mat4(1.0),mat4(1.0));",
            "out vec4 iris_TexCoord[4];",
            "#define gl_TexCoord iris_TexCoord",
            "out float iris_FogFragCoord;",
            "#define gl_FogFragCoord iris_FogFragCoord",
            "vec4 iris_shadow2D(sampler2DShadow s, vec3 p) { return vec4(texture(s, p)); }",
            "vec4 iris_shadow2DLod(sampler2DShadow s, vec3 p, float l) { return vec4(textureLod(s, p, l)); }",
            "#define ftransform() iris_Vertex",
            ""
    ) + "\n";

    /**
     * The generated vertex main is APPENDED after the pack body (not part of the prologue): the hoisted global
     * initializers it runs reference pack globals/uniforms that must already be declared above it.
     */
    private static String vertexMain(String hoistedAssignments) {
        return "\nvoid main() {\n"
                + "    iris_Vertex = a_Position;\n"
                + "    iris_MultiTexCoord0 = vec4(a_TexCoord, 0.0, 1.0);\n"
                + hoistedAssignments
                + "    irisMain();\n"
                + "}\n";
    }

    private static final String FRAGMENT_PROLOGUE = String.join("\n",
            "#version 330 core",
            "out vec4 iris_FragData[8];",
            "#define gl_FragColor iris_FragData[0]",
            "#define gl_FragData iris_FragData",
            "in vec4 iris_TexCoord[4];",
            "#define gl_TexCoord iris_TexCoord",
            "in float iris_FogFragCoord;",
            "#define gl_FogFragCoord iris_FogFragCoord",
            "vec4 iris_shadow2D(sampler2DShadow s, vec3 p) { return vec4(texture(s, p)); }",
            "vec4 iris_shadow2DLod(sampler2DShadow s, vec3 p, float l) { return vec4(textureLod(s, p, l)); }",
            ""
    ) + "\n";

    public static String transformVertexShader(String source) {
        String body = strip(source);
        body = renameMain(body);
        body = convertVaryings(body, "out");
        body = dropAttribute(body);
        body = modernize(body);
        // Pack globals initialized from uniforms are undefined under 330 (drivers may evaluate them before uniform
        // upload — zeros/NaNs); run those initializers at the top of the generated main, like GLSL 120 did.
        GlslGlobalInitHoister.Result hoist = GlslGlobalInitHoister.hoist(body);
        return VERTEX_PROLOGUE + hoist.body + vertexMain(hoist.hoistedAssignments);
    }

    /**
     * Fragment epilogue: wraps the pack's main so NaN outputs render MAGENTA instead of silently black — a NaN
     * anywhere in a composite (divide-by-zero, acos(>1), normalize(0)…) otherwise looks identical to "dark scene"
     * and is undiagnosable from screenshots.
     */
    private static final String FRAGMENT_EPILOGUE = String.join("\n",
            "",
            "void main() {",
            "    irisMain();",
            "    if (isnan(iris_FragData[0].r) || isnan(iris_FragData[0].g) || isnan(iris_FragData[0].b)) {",
            "        iris_FragData[0] = vec4(1.0, 0.0, 1.0, 1.0);",
            "    }",
            "}",
            ""
    );

    public static String transformFragmentShader(String source) {
        String body = strip(source);
        body = renameMain(body);
        body = convertVaryings(body, "in");
        body = modernize(body);
        GlslGlobalInitHoister.Result hoist = GlslGlobalInitHoister.hoist(body);
        return FRAGMENT_PROLOGUE + hoist.body
                + FRAGMENT_EPILOGUE.replace("    irisMain();", hoist.hoistedAssignments + "    irisMain();");
    }

    private static String strip(String source) {
        return VERSION.matcher(source).replaceFirst("");
    }

    /** Renames every {@code void main()} — flattened sources may hold several in mutually exclusive #ifdef branches. */
    private static String renameMain(String source) {
        return source.replaceAll("\\bvoid\\s+main\\s*\\(\\s*(void)?\\s*\\)", "void irisMain()");
    }

    private static String convertVaryings(String source, String direction) {
        return source.replaceAll("(?m)^(\\s*)varying\\b", "$1" + direction);
    }

    private static String dropAttribute(String source) {
        return source.replaceAll("(?m)^(\\s*)attribute\\s+", "$1");
    }

    private static String modernize(String source) {
        // A sampler literally named "texture" clashes with the 330 builtin; rename it first (the word boundary keeps
        // texture2D/texture2DLod untouched). The sampler-unit table maps "gtexture" to the same unit.
        source = source.replaceAll("\\btexture\\b", "gtexture");
        source = source.replaceAll("\\btexture2DLod\\b", "textureLod");
        source = source.replaceAll("\\btexture2D\\b", "texture");
        source = source.replaceAll("\\btexture3D\\b", "texture");
        // shadow2D must keep returning vec4 (packs swizzle .x/.z off it); the iris_ wrappers are in the prologues.
        source = source.replaceAll("\\bshadow2DLod\\b", "iris_shadow2DLod");
        source = source.replaceAll("\\bshadow2D\\b", "iris_shadow2D");
        return source;
    }
}
