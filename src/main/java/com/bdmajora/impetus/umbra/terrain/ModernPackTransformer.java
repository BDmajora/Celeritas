package com.bdmajora.impetus.umbra.terrain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Stage normalisation for modern single-source packs like Complementary and BSL, which compile the same file
// as both stages under #ifdef gates. Deliberately does almost nothing: the compatibility context's preprocessor
// handles the gates, so only #version is raised to 330 compatibility and the body is left as written
public final class ModernPackTransformer {
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#version\\s+(\\d+)(?:\\s+\\w+)?\\s*$");
    private static final Pattern UINT_DECLARATION = Pattern.compile(
            "(?m)^(\\s*(?:const\\s+)?uint\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*)([^;]+)(;.*)$");
    private static final Pattern UVEC_CONSTRUCTOR = Pattern.compile("\\buvec([234])\\s*\\(([^()]*)\\)");
    private static final Pattern VEC2_DECLARATION_FROM_FIXED_FUNCTION_VEC4 = Pattern.compile(
            "(?m)^(\\s*(?:const\\s+)?vec2\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*)([^;\\n]+)(;.*)$");
    private static final Pattern FIXED_FUNCTION_VEC4_TERM = Pattern.compile(
            "\\b(?:gl_MultiTexCoord[0-7]|gl_Color|gl_Vertex|mc_midTexCoord|iris_MultiTexCoord[0-7]"
                    + "|iris_Color|iris_Vertex|iris_MidTexFull)\\b");
    private static final Pattern TRAILING_SWIZZLE = Pattern.compile("\\.\\s*[xyzwrgastpq]{1,4}\\s*$");

    private ModernPackTransformer() {
    }

    // True when the source declares #version 130 or higher — the marker separating modern single-source packs from
    // the GLSL-120 Chocapic family FullscreenTransformer handles
    // Note this is a version check, not a feature check, and a 130-declaring pack can still use compatibility-profile
    // built-ins for real — see the fog rewrite below for what that cost once
    public static boolean isModernSource(String source) {
        if (source == null) {
            return false;
        }
        Matcher matcher = VERSION.matcher(source);
        return matcher.find() && Integer.parseInt(matcher.group(1)) >= 130;
    }

    // Normalises the #version and returns the source otherwise untouched
    // Nothing stage-specific happens here because the stage is already selected by the
    // #define VERTEX_SHADER / FRAGMENT_SHADER the .vsh/.fsh entry point carries
    public static String transform(String source) {
        String body = VERSION.matcher(source).replaceAll("");
        body = rewriteFogParameters(body);
        body = rewriteUnsignedStrictness(body);
        return "#version " + targetVersion(source) + " compatibility\n" + stripLeadingBlankLines(body);
    }

    // The compatibility version to compile at: at least 330, never BELOW the pack's own declaration —
    // Complementary's shadow stub declares 400 — and 430 when the source uses image load/store for
    // coloured-lighting voxelization, or other compute-adjacent features 330 simply lacks
    private static int targetVersion(String source) {
        int version = 330;
        java.util.regex.Matcher declared = VERSION.matcher(source);
        if (declared.find()) {
            version = Math.max(version, Integer.parseInt(declared.group(1)));
        }
        if (source.contains("imageStore") || source.contains("imageLoad") || source.contains("imageAtomic")) {
            version = Math.max(version, 430);
        }
        return version;
    }

    // So the #version line lands first, as GLSL requires
    private static String stripLeadingBlankLines(String source) {
        int start = 0;
        while (start < source.length()) {
            char c = source.charAt(start);
            if (c == '\n' || c == '\r') {
                start++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                start++;
                continue;
            }
            break;
        }
        return source.substring(start);
    }

    // Substitutes the gl_Fog.* built-ins with the live uniforms, sharing FogParameters with the other two
    // transformers so all three cannot drift apart
    //
    // gl_Fog.color used to be replaced with vec4(0.0), on the assumption that only genuinely modern 1.17+ packs
    // reach this transformer and any gl_Fog reference in one sits in a dead branch
    // That assumption is wrong. isModernSource keys off #version >= 130, and plenty of 1.12.2-era packs declare 130
    // while still using the compatibility-profile fog built-ins for real
    // Body Camera Shader v1.6.1 guards its cloud distance-fade with `if (gl_Fog.color.rgb != vec3(0.0))`, which the
    // substitution turned into `if (vec4(0.0).rgb != vec3(0.0))` — always false. Its clouds then never faded and
    // rendered as one opaque slab out to the cloud limit
    // A constant is especially dangerous for the colour precisely because packs use it as a PREDICATE, not just a
    // value
    //
    // The scalars were constants too and are now mapped as well, for Iris parity. That visibly changes packs which
    // read them in live code: Complementary and Spooklementary both compute
    // `float fog = (lViewPos * 3.0 - gl_Fog.start) * gl_Fog.scale;`, which was `(lViewPos * 3.0 - 0.0) * 1.0` — an
    // unbounded value rather than the 0..1 linear fog factor the expression is meant to produce — and is now the
    // real (dist - start) / (end - start)
    private static String rewriteFogParameters(String source) {
        if (!source.contains("gl_Fog")) {
            return source;
        }
        // Names already declared upstream (a prologue-carrying path) must not be declared a second time.
        boolean[] alreadyDeclared = new boolean[FogParameters.DECLARATIONS.length];
        for (int i = 0; i < FogParameters.DECLARATIONS.length; i++) {
            alreadyDeclared[i] = source.contains(identifierOf(FogParameters.DECLARATIONS[i]));
        }

        source = FogParameters.rewrite(source);

        StringBuilder declarations = new StringBuilder();
        for (int i = 0; i < FogParameters.DECLARATIONS.length; i++) {
            String identifier = identifierOf(FogParameters.DECLARATIONS[i]);
            if (!alreadyDeclared[i] && source.contains(identifier)) {
                declarations.append(FogParameters.DECLARATIONS[i]).append('\n');
            }
        }
        return declarations.length() == 0 ? source : injectAfterPreamble(source, declarations.toString());
    }

    // Pulls the identifier out of a declaration string: "uniform vec4 iris_FogColor;" gives "iris_FogColor"
    private static String identifierOf(String declaration) {
        String trimmed = declaration.trim();
        int end = trimmed.lastIndexOf(';');
        int start = trimmed.lastIndexOf(' ', end);
        return trimmed.substring(start + 1, end);
    }

    // The real named attribute that feeds gl_MultiTexCoord0 on the full-screen quad, since the built-in cannot be
    // relied on there — see bindFullscreenTexCoord below
    public static final String FULLSCREEN_TEXCOORD_ATTRIBUTE = "iris_QuadTexCoord";

    private static final Pattern MULTI_TEX_COORD_0 =
            Pattern.compile("(?<![A-Za-z0-9_])gl_MultiTexCoord0(?![A-Za-z0-9_])");

    // Rewrites gl_MultiTexCoord0 in a full-screen-pass vertex shader to read a real named vertex attribute
    //
    // Composite, deferred and final sources are `#version <n> compatibility` and address the quad through the
    // fixed-function built-ins — Complementary writes `texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;`
    // Feeding that built-in by writing generic vertex attribute 8 only works on NVIDIA, because the
    // generic-to-conventional aliasing table that maps 8 to gl_MultiTexCoord0 comes from NV_vertex_program
    // GL guarantees aliasing for attribute 0 (gl_Vertex) and NOTHING ELSE, and Mesa implements exactly that
    // So on Mesa the built-in kept its default (0,0,0,1), every vertex of the quad got texCoord = (0,0), and every
    // composite pass sampled one corner texel across the whole screen — a uniform image whose colour changed as
    // that single texel did
    // It compiled, linked and drew without a single GL error, which is why nothing in the logs pointed at it
    //
    // Iris never relies on the aliasing: it binds a real named vertex format and substitutes the built-in, mapping
    // gl_MultiTexCoord0 to vec4(UV0, 0.0, 1.0) plus an injected `in vec2 UV0;`. This does the same
    // Substitution rather than a #define is deliberate — GLSL reserves macro names beginning with gl_, so defining
    // over the built-in is itself illegal on a strict compiler
    //
    // Only the full-screen path needs this. Gbuffer programs get gl_MultiTexCoord0 from Minecraft's own
    // fixed-function texture-coordinate arrays, which ARE the conventional attribute and work everywhere
    public static String bindFullscreenTexCoord(String vertexSource) {
        if (vertexSource == null || !vertexSource.contains("gl_MultiTexCoord0")) {
            return vertexSource;
        }
        String rewritten = MULTI_TEX_COORD_0.matcher(vertexSource).replaceAll(
                Matcher.quoteReplacement("vec4(" + FULLSCREEN_TEXCOORD_ATTRIBUTE + ", 0.0, 1.0)"));
        return injectAfterPreamble(rewritten, "in vec2 " + FULLSCREEN_TEXCOORD_ATTRIBUTE + ";");
    }

    // Inserts declarations immediately before the first line carrying a real (non-preprocessor) token, skipping
    // blanks, comments and # directives
    // Not simply prepended, because GLSL requires every #extension to precede any real token — putting a
    // declaration above them breaks every pack that uses one
    private static String injectAfterPreamble(String source, String declarations) {
        String[] lines = source.split("\n", -1);
        boolean inBlockComment = false;
        int insertAt = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (inBlockComment) {
                int close = trimmed.indexOf("*/");
                if (close < 0) {
                    insertAt = i + 1;
                    continue;
                }
                inBlockComment = false;
                trimmed = trimmed.substring(close + 2).trim();
            }
            while (trimmed.startsWith("/*")) {
                int close = trimmed.indexOf("*/", 2);
                if (close < 0) {
                    inBlockComment = true;
                    trimmed = "";
                    break;
                }
                trimmed = trimmed.substring(close + 2).trim();
            }
            if (inBlockComment || trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) {
                insertAt = i + 1;
                continue;
            }
            insertAt = i;
            break;
        }
        StringBuilder out = new StringBuilder(source.length() + declarations.length());
        for (int i = 0; i < lines.length; i++) {
            if (i == insertAt) {
                out.append(declarations);
            }
            out.append(lines[i]);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        if (insertAt >= lines.length) {
            out.append(declarations);
        }
        return out.toString();
    }

    // Fixes unsigned constructs 330 rejects that 130 tolerated
    static String rewriteUnsignedStrictness(String source) {
        Matcher constUint = UINT_DECLARATION.matcher(source);
        StringBuffer rewritten = new StringBuffer(source.length());
        while (constUint.find()) {
            String expression = constUint.group(2).trim();
            constUint.appendReplacement(rewritten, Matcher.quoteReplacement(
                    constUint.group(1) + "uint(" + expression + ")" + constUint.group(3)));
        }
        constUint.appendTail(rewritten);
        return rewriteFixedFunctionVec2Narrowing(rewriteUnsignedVectorConstructors(rewritten.toString()));
    }

    // uvec constructors from signed literals
    private static String rewriteUnsignedVectorConstructors(String source) {
        Matcher constructor = UVEC_CONSTRUCTOR.matcher(source);
        StringBuffer rewritten = new StringBuffer(source.length());
        while (constructor.find()) {
            String[] args = constructor.group(2).split(",", -1);
            boolean changed = false;
            for (int i = 0; i < args.length; i++) {
                String trimmed = args[i].trim();
                if (trimmed.matches("\\d+")) {
                    args[i] = args[i].replaceFirst("\\d+", trimmed + "u");
                    changed = true;
                }
            }
            if (changed) {
                constructor.appendReplacement(rewritten, Matcher.quoteReplacement(
                        "uvec" + constructor.group(1) + "(" + String.join(",", args) + ")"));
            } else {
                constructor.appendReplacement(rewritten, Matcher.quoteReplacement(constructor.group(0)));
            }
        }
        constructor.appendTail(rewritten);
        return rewritten.toString();
    }

    // Explicit .xy where a vec4 built-in is assigned to a vec2
    private static String rewriteFixedFunctionVec2Narrowing(String source) {
        Matcher declaration = VEC2_DECLARATION_FROM_FIXED_FUNCTION_VEC4.matcher(source);
        StringBuffer rewritten = new StringBuffer(source.length());
        while (declaration.find()) {
            String expression = declaration.group(2).trim();
            if (FIXED_FUNCTION_VEC4_TERM.matcher(expression).find()
                    && !TRAILING_SWIZZLE.matcher(expression).find()
                    && !expression.startsWith("vec2(")) {
                declaration.appendReplacement(rewritten, Matcher.quoteReplacement(
                        declaration.group(1) + "(" + expression + ").xy" + declaration.group(3)));
            } else {
                declaration.appendReplacement(rewritten, Matcher.quoteReplacement(declaration.group(0)));
            }
        }
        declaration.appendTail(rewritten);
        return rewritten.toString();
    }
}
