package com.bdmajora.impetus.umbra.terrain;

import com.bdmajora.impetus.umbra.gl.program.DrawBuffers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Lifts an OptiFine-style GLSL-120 gbuffers_terrain program to run on Impetus's uncompressed chunk vertex format
// (a_PosId/a_Color/a_TexCoord/a_LightCoord) and matrices instead of fixed-function state.
// 1.12.2 analogue of modern Umbra's Sodium terrain transform: bumps the shader to #version 330 core, renames the
// pack's main to irisMain, and generates a wrapper main that decodes the vertex into globals the gl_* #defines point at.
public final class ImpetusTerrainTransformer {
    private static final Pattern VERSION = Pattern.compile("^\\s*#version[^\\n]*\\n", Pattern.MULTILINE);

    private ImpetusTerrainTransformer() {
    }

    // Vertex prologue: Impetus attributes/uniforms + vertex decode + gl_* built-in aliases + main() wrapper.
    private static final String VERTEX_PROLOGUE = String.join("\n",
            "#version 330 core",
            "// ---- Impetus/Umbra terrain bridge (generated) ----",
            "in vec3 a_PosId;",
            "in vec4 a_Color;",
            "in vec2 a_TexCoord;",
            "in uint a_LightCoord;",
            "in vec4 iris_Normal;",      // true face normal, NormI8 (normalized signed bytes)
            "in vec4 iris_Tangent;",     // at_tangent, w = handedness
            "in vec2 iris_MidTexCoord;", // centre of the quad's texture region in atlas UV (not the sprite centre)
            "in vec4 iris_BlockInfo;",   // mc_Entity: (block id, render type, metadata, 1)
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
            "#define gl_ModelViewMatrix u_ModelViewMatrix",
            "#define gl_ProjectionMatrix u_ProjectionMatrix",
            "#define gl_ModelViewProjectionMatrix (u_ProjectionMatrix * u_ModelViewMatrix)",
            "#define gl_NormalMatrix (mat3(transpose(inverse(u_ModelViewMatrix))))",
            "#define gl_TextureMatrix iris_TextureMatrix",
            "#define ftransform() (u_ProjectionMatrix * (u_ModelViewMatrix * iris_Vertex))",
            "out float iris_FogFragCoord;",
            "#define gl_FogFragCoord iris_FogFragCoord",
            // gl_Fog.* stand-ins: real per-frame uniforms (fed by CommonUniforms), NOT constants. See the
            // matching note in FullscreenTransformer — const 0.0/1.0/vec4(0) forces full-strength fog for
            // packs that read gl_Fog.start/end/color. Unused ones are stripped by the compiler.
            "uniform vec4 iris_FogColor;",
            "uniform float iris_FogDensity;",
            "uniform float iris_FogStart;",
            "uniform float iris_FogEnd;",
            // gl_Fog.scale is inlined as an expression by FogParameters (Umbra's 1/(end-start)), so there is
            // deliberately no iris_FogScale declaration here.
            "out vec4 iris_TexCoordArr[4];",
            "#define gl_TexCoord iris_TexCoordArr",
            "// OptiFine packs rely on fixed-function GL_ALPHA_TEST for cutout transparency, but Impetus disables it",
            "// and discards in-shader instead; mirror its per-material cutoff (bits 1-2 of the material byte).",
            "const float[4] _UMBRA_ALPHA_CUTOFF = float[4](0.0, 0.1, 0.5, 1.0);",
            "flat out float iris_AlphaCutoff;",
            "// ---- end generated prologue ----",
            ""
    ) + "\n";

    // Appended after the pack body: the hoisted global initializers it runs reference pack globals/uniforms
    // that must already be declared above it.
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
                + "    iris_EntityFull = iris_BlockInfo;\n"
                + "    iris_AlphaCutoff = _UMBRA_ALPHA_CUTOFF[int((lightData >> 1u) & 3u)];\n"
                + hoistedAssignments
                + "    irisMain();\n"
                // Robustness guard for a non-finite clip position out of the pack's vertex math.
                //
                // This used to collapse the vertex to vec4(0, 0, 2, 1) — behind the far plane, so the triangle clipped
                // away. That fixed the original symptom (one flung vertex dragging a sliver "grass spike" across the
                // screen) but created a far worse one: when the cause is a *uniform* rather than one bad vertex, every
                // terrain vertex in the frame is non-finite, so this deleted the entire world. Both terrain passes
                // carry this guard and the entity programs do not, which is exactly the recurring "world unloads
                // randomly, signs and armour stands keep drawing" report.
                //
                // Fall back to the plain transform instead. u_ProjectionMatrix, u_ModelViewMatrix and iris_Vertex are
                // supplied by this pipeline, not by the pack, and are finite whenever the draw itself is valid — so
                // the vertex lands where it geometrically belongs. A single bad vertex still cannot spike (it gets its
                // real position), and a bad uniform now costs the pack's vertex effects for a frame instead of the
                // whole world.
                + "    if (any(isnan(gl_Position)) || any(isinf(gl_Position))) {\n"
                + "        gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * iris_Vertex;\n"
                + "    }\n"
                + "}\n";
    }

    // gl_FragData replacement, only emitted when the pack body actually references gl_FragData/gl_FragColor —
    // the location-0 array would otherwise collide with named layout(location=N) outputs (photon).
    // Array size comes from the driver (DrawBuffers.fragmentOutputArraySize()); a hardcoded 16 exceeded
    // GL_MAX_DRAW_BUFFERS and failed to link on Mesa/Arc.
    private static String fragDataBlock() {
        return String.join("\n",
                "layout(location = 0) out vec4 iris_FragData[" + DrawBuffers.fragmentOutputArraySize() + "];",
                "#define gl_FragColor iris_FragData[0]",
                "#define gl_FragData iris_FragData",
                ""
        );
    }

    // The in-shader stand-in for the fixed-function alpha test, threshold picked to match Umbra's per-pass default
    // (TERRAIN_SOLID = ALWAYS/no discard, TERRAIN_CUTOUT = 0.5, TERRAIN_TRANSLUCENT = 0.0001) instead of the old
    // per-vertex material-byte cutoff, which had no counterpart in Umbra and could discard the whole world if the
    // packed bits read 3 (cutoff 1.0, everything under full alpha dropped).
    private static String alphaDiscard(String snippet) {
        return snippet == null ? "" : snippet;
    }

    // Fragment prologue: promotes the GLSL 120 fragment built-ins a legacy pack uses to their 330-core equivalents,
    // and declares the outputs and uniforms the generated code below references
    private static final String FRAGMENT_PROLOGUE = String.join("\n",
            "#version 330 core",
            "// ---- Impetus/Umbra terrain bridge (generated) ----",
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
            // gl_Fog.* stand-ins: real per-frame uniforms (fed by CommonUniforms), NOT constants. See the
            // matching note in FullscreenTransformer — const 0.0/1.0/vec4(0) forces full-strength fog for
            // packs that read gl_Fog.start/end/color. Unused ones are stripped by the compiler.
            "uniform vec4 iris_FogColor;",
            "uniform float iris_FogDensity;",
            "uniform float iris_FogStart;",
            "uniform float iris_FogEnd;",
            // gl_Fog.scale is inlined as an expression by FogParameters (Umbra's 1/(end-start)), so there is
            // deliberately no iris_FogScale declaration here.
            "in vec4 iris_TexCoordArr[4];",
            "#define gl_TexCoord iris_TexCoordArr",
            "flat in float iris_AlphaCutoff;",
            "// ---- end generated prologue ----",
            ""
    ) + "\n";

    // Full vertex rewrite: version bump, main rename, generated decode prologue
    public static String transformVertexShader(String source) {
        String body = stripVersion(source);
        body = renameMain(body);
        body = convertVaryings(body, "out");
        body = dropAttributeStorageQualifier(body);
        body = modernizeCommon(body);
        // Pack globals initialized from uniforms are undefined under 330 (drivers may evaluate them before uniform
        // upload — zeros/NaNs); run those initializers at the top of the generated main, like GLSL 120 did.
        GlslGlobalInitHoister.Result hoist = GlslGlobalInitHoister.hoist(body);
        return VERTEX_PROLOGUE + attributeAdapterDefines(source) + hoist.body + vertexMain(hoist.hoistedAssignments);
    }

    // Fragment rewrite with default draw buffers
    public static String transformFragmentShader(String source) {
        return transformFragmentShader(source, DrawBuffers.DEFAULT);
    }

    // Fragment rewrite routing gl_FragData to the given targets
    public static String transformFragmentShader(String source, int[] drawBuffers) {
        return transformFragmentShader(source, drawBuffers,
                "    if (iris_FragData[0].a < iris_AlphaCutoff) { discard; }\n");
    }

    // Fragment rewrite with an injected alpha test, for cutout passes
    public static String transformFragmentShader(String source, int[] drawBuffers, String alphaTestSnippet) {
        String body = stripVersion(source);
        body = renameMain(body);
        body = convertVaryings(body, "in");
        body = modernizeCommon(body);
        body = DrawBuffers.rewriteFragmentOutputs(body, drawBuffers);
        GlslGlobalInitHoister.Result hoist = GlslGlobalInitHoister.hoist(body);
        String transformed = FRAGMENT_PROLOGUE + fragDataBlock() + hoist.body
                + "\nvoid main() {\n" + hoist.hoistedAssignments + "    irisMain();\n"
                + alphaDiscard(alphaTestSnippet)
                + "}\n";
        return transformed;
    }

    // ------------------------------------------------------------------ modern (#version 130+) terrain

    // The same vertex bridge, but for modern single-source dual-stage packs like Complementary
    // Kept: the attribute decode, the gl_* -> Impetus defines, and the generated main
    // Dropped: every transform that assumes GLSL-120 Chocapic structure — no varying conversion, because the pack
    // already flips in/out itself behind #ifdef VERTEX_SHADER; no global hoisting; and crucially NO
    // texture -> gtexture rename, since modern packs call the texture() built-in everywhere and that rename is
    // exactly what corrupted them
    // The stage guards and option gates are left to the driver's own preprocessor, which the compatibility profile
    // provides
    // renameMain still runs: it renames BOTH stages' void main() to irisMain, and only the active one survives the
    // driver's #ifdef anyway
    public static String transformVertexShaderModern(String source) {
        String body = stripVersion(source);
        body = renameMain(body);
        // Delete the pack's mc_Entity/mc_midTexCoord/at_tangent attribute declarations; the prologue #defines those
        // names onto its own decoded globals, so the pack's declarations would become illegal redeclarations.
        body = dropAttributeStorageQualifier(body);
        body = rewriteFogParameters(body);
        body = ModernPackTransformer.rewriteUnsignedStrictness(body);
        return compatFor(VERTEX_PROLOGUE, source) + attributeAdapterDefines(source) + body + vertexMain("");
    }

    // Modern-pack variant that leaves the body alone
    public static String transformFragmentShaderModern(String source) {
        return transformFragmentShaderModern(source, DrawBuffers.DEFAULT);
    }

    // Modern-pack variant with draw buffer routing
    public static String transformFragmentShaderModern(String source, int[] drawBuffers) {
        return transformFragmentShaderModern(source, drawBuffers,
                "    if (iris_FragData[0].a < iris_AlphaCutoff) { discard; }\n");
    }

    // Modern-pack variant with alpha test
    public static String transformFragmentShaderModern(String source, int[] drawBuffers, String alphaTestSnippet) {
        String body = stripVersion(source);
        body = renameMain(body);
        body = rewriteFogParameters(body);
        body = ModernPackTransformer.rewriteUnsignedStrictness(body);
        body = DrawBuffers.rewriteFragmentOutputs(body, drawBuffers);
        // Umbra parity: packs that write gl_FragData/gl_FragColor (OptiFine style) get the generated output array
        // and the injected alpha test; packs using named layout(location) outputs (photon) keep their declarations
        // — the 16-array would collide with their output locations — and handle cutout discard themselves.
        boolean usesFragData = Pattern.compile("\\bgl_Frag(?:Data|Color)\\b").matcher(body).find();
        String transformed = compatFor(FRAGMENT_PROLOGUE, source)
                + (usesFragData ? fragDataBlock() : "")
                + body
                + (usesFragData
                        ? "\nvoid main() {\n    irisMain();\n"
                                + alphaDiscard(alphaTestSnippet)
                                + "}\n"
                        : "\nvoid main() {\n    irisMain();\n}\n");
        return transformed;
    }

    private static final Pattern DECLARED_VERSION = Pattern.compile("#version\\s+(\\d+)");

    // The compatibility version to compile a modern pack at
    // Never below 330, which the prologue itself needs; never below the pack's OWN declaration, since Photon
    // declares 400 and relies on 400 semantics such as implicit int-to-uint conversion; and 430 when the source
    // uses image load/store for coloured-lighting voxelization
    private static String compatFor(String prologue, String packBody) {
        int version = 330;
        Matcher declared = DECLARED_VERSION.matcher(packBody);
        if (declared.find()) {
            version = Math.max(version, Integer.parseInt(declared.group(1)));
        }
        if (packBody.contains("imageStore") || packBody.contains("imageLoad")
                || packBody.contains("imageAtomic")) {
            version = Math.max(version, 430);
        }
        return prologue.replaceFirst("#version 330 core", "#version " + version + " compatibility");
    }

    // ------------------------------------------------------------------ OptiFine attribute type adaptation

    private static final Pattern SPECIAL_ATTRIBUTE_DECL = Pattern.compile(
            "(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?"
                    + "(?:(?:flat|smooth|noperspective|centroid|sample|invariant)\\s+)*"
                    + "(?:attribute|in)\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+"
                    + "(mc_Entity|mc_midTexCoord|at_tangent|at_midBlock)\\s*;");

    // The OptiFine attribute defines, adapted to the type each attribute is DECLARED with in this pack's source —
    // the declarations themselves having been deleted by dropAttributeStorageQualifier
    // The adaptation is necessary because the two eras disagree: OptiFine-era packs declare
    // `attribute vec4 mc_midTexCoord;` while Iris-native packs use the modern types, vec2 mc_midTexCoord, vec3
    // mc_Entity, vec3 at_midBlock
    // Pointing a vec2-typed usage at a vec4 global is a hard compile error, so the define has to match the pack's
    // own view of the type. Mirrors Iris's SodiumTransformer dimension adaptation
    private static String attributeAdapterDefines(String packSource) {
        java.util.Map<String, String> declaredTypes = new java.util.HashMap<>();
        Matcher decl = SPECIAL_ATTRIBUTE_DECL.matcher(packSource);
        while (decl.find()) {
            declaredTypes.putIfAbsent(decl.group(2), decl.group(1));
        }
        return "#define mc_Entity " + adaptTo(declaredTypes.get("mc_Entity"), "iris_EntityFull") + "\n"
                + "#define mc_midTexCoord " + adaptTo(declaredTypes.get("mc_midTexCoord"), "iris_MidTexFull") + "\n"
                + "#define at_tangent " + adaptTo(declaredTypes.get("at_tangent"), "iris_Tangent") + "\n"
                + "#define at_midBlock " + adaptTo(declaredTypes.get("at_midBlock"), "iris_MidBlock") + "\n";
    }

    // Narrows the vec4 bridge global to whatever type the pack declared, by swizzling — an absent declaration
    // leaves it as vec4, which is the OptiFine-era default
    private static String adaptTo(String declaredType, String vec4Global) {
        if (declaredType == null) {
            return vec4Global;
        }
        switch (declaredType) {
            case "vec3":
                return "(" + vec4Global + ".xyz)";
            case "vec2":
                return "(" + vec4Global + ".xy)";
            case "float":
                return "(" + vec4Global + ".x)";
            case "int":
                return "int(" + vec4Global + ".x)";
            case "uint":
                return "uint(max(" + vec4Global + ".x, 0.0))";
            case "ivec2":
                return "ivec2(" + vec4Global + ".xy)";
            default:
                return vec4Global;
        }
    }

    // Removes the pack's #version so the transform can emit its own
    private static String stripVersion(String source) {
        return VERSION.matcher(source).replaceFirst("");
    }

    // Renames the pack's void main() to irisMain so the generated main can wrap it
    // EVERY occurrence, not just the first: an include-flattened source can hold several main definitions in
    // mutually exclusive #ifdef branches, and this rewrite runs before any preprocessing, so all of them are still
    // textually present
    private static String renameMain(String source) {
        return source.replaceAll("\\bvoid\\s+main\\s*\\(\\s*(void)?\\s*\\)", "void irisMain()");
    }

    // varying becomes out in the vertex stage and in in the fragment stage, PRESERVING any qualifier in front of it
    // Handling that prefix is not optional. GLSL 120 permits invariant and centroid before varying, and packs also
    // write `flat varying` — illegal by the letter of GLSL 120, but NVIDIA's compatibility compiler accepts it, so
    // packs ship it
    // Anchoring this pattern at ^\s*varying silently skips every one of those lines, and a surviving `flat varying`
    // is a hard error once the stage is lifted to 330 core: C7560 "does not allow 'flat' with 'varying'" plus
    // C7561 "requires 'in/out' with 'flat'"
    // That killed miniature-shader's gbuffers_terrain over its `flat varying float lightSourceLevel`, so terrain
    // fell back to the Impetus default program and the whole world rendered vanilla while every other stage used
    // the pack
    // GLSL 330 keeps the same qualifier order — invariant, then interpolation, then storage — so emitting the
    // captured prefix verbatim in front of in/out is correct: `flat varying` becomes `flat out`
    private static String convertVaryings(String source, String direction) {
        return source.replaceAll(
                "(?m)^(\\s*)((?:(?:invariant|flat|smooth|noperspective|centroid)\\s+)*)varying\\b",
                "$1$2" + direction);
    }

    // Strips the `attribute` storage qualifier off the pack's OptiFine extra-attribute declarations
    // Two things it achieves: `attribute` is not a legal 330-core keyword at all, and removing it turns the
    // declaration into a plain global that attributeAdapterDefines can then alias onto the real bridge value
    private static String dropAttributeStorageQualifier(String source) {
        // mc_Entity / mc_midTexCoord / at_tangent are now REAL attributes fed by UmbraChunkVertexType; the prologue
        // #defines those names onto its own inputs, so the pack's declarations must be deleted outright (the define
        // would otherwise rewrite them into duplicate declarations of the prologue globals).
        source = source.replaceAll("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?"
                + "(?:(?:flat|smooth|noperspective|centroid|sample|invariant)\\s+)*"
                + "(?:attribute|in)\\s+(?:(?:lowp|mediump|highp)\\s+)?\\w+\\s+"
                + "(?:mc_Entity|mc_midTexCoord|at_tangent|at_midBlock)\\b"
                + "(?:\\s*=\\s*[^;]+)?\\s*;\\s*(?://.*)?$", "");
        // Any other attribute becomes an explicitly zero-initialized global — an uninitialized global is undefined.
        source = source.replaceAll("(?m)^(\\s*)attribute\\s+(\\w+)\\s+(\\w+)\\s*;", "$1$2 $3 = $2(0.0);");
        // Fallback for forms the initializer rewrite doesn't cover (e.g. multiple declarators): just drop the keyword.
        return source.replaceAll("(?m)^(\\s*)attribute\\s+", "$1");
    }

    // The keyword modernisations both stages need for 330 core — the ones that are pure renames with no
    // stage-specific handling
    private static String modernizeCommon(String source) {
        source = rewriteFogParameters(source);
        source = ModernPackTransformer.rewriteUnsignedStrictness(source);
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

    // gl_Fog.* onto the pipeline's fog uniforms
    private static String rewriteFogParameters(String source) {
        return FogParameters.rewrite(source);
    }
}
