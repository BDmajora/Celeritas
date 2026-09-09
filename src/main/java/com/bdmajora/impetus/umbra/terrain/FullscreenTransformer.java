package com.bdmajora.impetus.umbra.terrain;

import com.bdmajora.impetus.umbra.gl.program.DrawBuffers;

import java.util.regex.Pattern;

/**
 * Transforms a GLSL-120 full-screen shader-pack program ({@code composite}/{@code deferred}/{@code final}) to
 * {@code #version 330 core} for Impetus. Full-screen passes are trivial in the vertex stage (they just pass the
 * fullscreen quad's position/texcoord through), so this injects an {@code a_Position}/{@code a_TexCoord} attribute
 * pair, aliases the {@code gl_*} built-ins to them (with an ortho model-view-projection so {@code ftransform()} /
 * {@code gl_ModelViewProjectionMatrix * gl_Vertex} map the {@code [0,1]} quad to NDC {@code [-1,1]}), and promotes
 * fragment outputs. The pack's {@code colortexN}/{@code depthtexN} samplers are left as-is (bound by the pipeline).
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
            // Ortho that maps the [0,1] fullscreen quad to NDC [-1,1] (column-major; M*(x,y,z,1)=(2x-1,2y-1,0,1)),
            // matching Umbra's composite gl_ProjectionMatrix and the modern path's pushFullscreenFixedFunctionMatrices.
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

    /**
     * The array length is queried from the driver rather than hardcoded — see
     * {@link DrawBuffers#fragmentOutputArraySize()}. A fixed 16 demands 16 contiguous fragment-output locations,
     * which exceeds {@code GL_MAX_DRAW_BUFFERS} and fails to link on Mesa.
     */
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
            // gl_Fog.* stand-ins. These MUST be real per-frame uniforms (fed by CommonUniforms), not
            // constants: on OptiFine 1.12.2 (where IS_IRIS is undefined and MC_VERSION < 11802) packs such
            // as Sildur's read gl_Fog.start/end/color in their composite fog and expect the live linear
            // terrain-fog values. Emitting them as const 0.0/1.0/vec4(0) forces (dist - 0)/(1 - 0) >= 1 ->
            // full-strength fog, washing the whole scene to the sky colour (the "everything above water is
            // blue" haze). Unused ones are stripped by the compiler, so this is a no-op for packs that
            // don't reference gl_Fog.
            "uniform vec4 iris_FogColor;",
            "uniform float iris_FogDensity;",
            "uniform float iris_FogStart;",
            "uniform float iris_FogEnd;",
            // gl_Fog.scale is inlined as an expression by FogParameters (Umbra's 1/(end-start)), so there is
            // deliberately no iris_FogScale declaration here.
            "vec4 iris_shadow2D(sampler2DShadow s, vec3 p) { return vec4(texture(s, p)); }",
            "vec4 iris_shadow2DLod(sampler2DShadow s, vec3 p, float l) { return vec4(textureLod(s, p, l)); }",
            ""
        ) + "\n";
    }

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

    // Fragment epilogue: wraps the pack's main and scrubs non-finite components out of every slot the pass writes
    // A NaN left in place spreads — it survives the composite chain, poisons the TAA history buffer, and turns a
    // one-pixel divide-by-zero into a permanent smear — so replacing it with 0 is the robust behaviour
    private static String fragmentEpilogue(int[] drawBuffers) {
        StringBuilder out = new StringBuilder("\nvoid main() {\n    irisMain();\n");
        int slots = drawBuffers == null ? 1 : Math.max(1, drawBuffers.length);
        for (int slot = 0; slot < slots; slot++) {
            String target = "iris_FragData[" + slot + "]";
            // Per-component so a NaN in one channel does not discard the other three. Two mixes rather than one:
            // `||` is a scalar-bool operator in GLSL, there is no component-wise or() for bvec4.
            out.append("    ").append(target).append(" = mix(").append(target)
                    .append(", vec4(0.0), isnan(").append(target).append("));\n");
            out.append("    ").append(target).append(" = mix(").append(target)
                    .append(", vec4(0.0), isinf(").append(target).append("));\n");
        }
        return out.append("}\n").toString();
    }

    public static String transformFragmentShader(String source) {
        return transformFragmentShader(source, DrawBuffers.DEFAULT);
    }

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

    private static String strip(String source) {
        return VERSION.matcher(source).replaceFirst("");
    }

    /** Renames every {@code void main()} — flattened sources may hold several in mutually exclusive #ifdef branches. */
    private static String renameMain(String source) {
        return source.replaceAll("\\bvoid\\s+main\\s*\\(\\s*(void)?\\s*\\)", "void irisMain()");
    }

    /**
     * {@code varying} → {@code out}/{@code in}, keeping any qualifier in front of it ({@code flat}, {@code centroid},
     * {@code invariant}, ...). Anchoring at {@code ^\s*varying} would skip {@code flat varying}, which is then a hard
     * error at 330 core (C7560/C7561) — see {@code ImpetusTerrainTransformer.convertVaryings} for the full story.
     */
    private static String convertVaryings(String source, String direction) {
        return source.replaceAll(
                "(?m)^(\\s*)((?:(?:invariant|flat|smooth|noperspective|centroid)\\s+)*)varying\\b",
                "$1$2" + direction);
    }

    private static String dropAttribute(String source) {
        return source.replaceAll("(?m)^(\\s*)attribute\\s+", "$1");
    }

    private static String modernize(String source) {
        source = rewriteLegacyProjectionProducts(source);
        source = rewriteFogParameters(source);
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

    /**
     * Some GLSL 120 OptiFine-era packs project a view-space direction as {@code vec4(dir, 1.0) * gbufferProjection}.
     * With our normal column-major matrix uploads that treats the perspective matrix as transposed, sending effects
     * such as Sildur's godray source far off-screen. Normalize those projection products to the GLSL column-vector
     * form while leaving model-view and inverse math alone.
     */
    private static String rewriteLegacyProjectionProducts(String source) {
        return source.replaceAll(
                "\\bvec4\\s*\\(([^;\\n]+)\\)\\s*\\*\\s*\\b(gbufferProjection|gbufferPreviousProjection|shadowProjection)\\b",
                "$2 * vec4($1)");
    }

    private static String rewriteFogParameters(String source) {
        return FogParameters.rewrite(source);
    }
}
