package com.bdmajora.impetus.iris.terrain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shader-stage normalization for <em>modern</em> shader packs (Complementary, BSL, …) — the ones written as
 * {@code #version 130+} single-source files that are compiled as both stages, selected by {@code #define VERTEX_SHADER}
 * / {@code #define FRAGMENT_SHADER} in the {@code .vsh}/{@code .fsh} entry points and gated with
 * {@code #ifdef VERTEX_SHADER}/{@code FRAGMENT_SHADER}.
 * <p>
 * Unlike {@link FullscreenTransformer} (which rewrites GLSL-120 Chocapic packs into 330-core, renaming {@code main},
 * converting {@code varying}, hoisting globals — surgery that <b>corrupts</b> a 5000-line modern source), this path
 * does almost nothing: Minecraft 1.12.2 runs on a GL <em>compatibility</em> context, so the driver's own preprocessor
 * already evaluates the stage {@code #ifdef}s and the option {@code #if} gates, and the legacy built-ins
 * ({@code gl_FragCoord}, {@code gl_FragData}, {@code gl_Vertex}, {@code ftransform}, …) the pack relies on are all
 * available. We only normalise the {@code #version} line up to {@code 330 compatibility} (a superset of 130 that keeps
 * every legacy feature while allowing the modern intrinsics — {@code texelFetch}, {@code textureLod}, … — these packs
 * also use), and leave the (already include-flattened) body exactly as the pack author wrote it.
 * <p>
 * The draw-buffer routing and fragment output locations are handled outside this class so every terrain and fullscreen
 * path uses the same Iris-style target mapping.
 */
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

    /**
     * @return {@code true} if {@code source} declares {@code #version 130} or higher — the marker that separates modern
     * single-source packs from the GLSL-120 Chocapic family that {@link FullscreenTransformer} handles.
     */
    public static boolean isModernSource(String source) {
        if (source == null) {
            return false;
        }
        Matcher matcher = VERSION.matcher(source);
        return matcher.find() && Integer.parseInt(matcher.group(1)) >= 130;
    }

    /**
     * Normalise the {@code #version} to {@code 330 compatibility} and otherwise return the source untouched. The stage
     * is already selected by the {@code #define VERTEX_SHADER}/{@code FRAGMENT_SHADER} that the {@code .vsh}/{@code .fsh}
     * entry point carries, so nothing stage-specific is needed here.
     */
    public static String transform(String source) {
        String body = VERSION.matcher(source).replaceAll("");
        body = rewriteFogParameters(body);
        body = rewriteUnsignedStrictness(body);
        return "#version " + targetVersion(source) + " compatibility\n" + stripLeadingBlankLines(body);
    }

    /**
     * The compatibility version to compile at: at least 330 (the lift this transformer performs), never lower than
     * the pack's own declaration (Complementary's shadow stub declares 400), and 430 when the source uses image
     * load/store (colored-lighting voxelization) or compute-adjacent features that 330 lacks.
     */
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

    /**
     * Substitutes the {@code gl_Fog.*} built-ins. {@code gl_Fog.color} maps to the live {@code iris_FogColor} uniform
     * (the one {@link FullscreenTransformer} and {@link ImpetusTerrainTransformer} already use); the scalars keep the
     * constant stand-ins they have always had.
     * <p>
     * {@code gl_Fog.color} used to be replaced with {@code vec4(0.0)}, on the assumption that only genuinely modern
     * (1.17+) packs reach this transformer, where any {@code gl_Fog} reference sits in a dead branch. That assumption
     * is wrong: {@link #isModernSource} keys off {@code #version >= 130}, and plenty of 1.12.2-era packs declare 130
     * while still using the compatibility-profile fog built-ins for real. Body Camera Shader v1.6.1 guards its cloud
     * distance-fade with {@code if (gl_Fog.color.rgb != vec3(0.0))}, which the substitution turned into
     * {@code if (vec4(0.0).rgb != vec3(0.0))} — always false. Its clouds then never faded and rendered as one opaque
     * slab out to the cloud limit. A constant is especially dangerous for the colour because packs use it as a
     * <em>predicate</em>, not just a value.
     * <p>
     * The scalars used to be left as constants too. They are now mapped as well, for Iris parity — see
     * {@link FogParameters}. That visibly changes packs which read them in live code: Complementary and Spooklementary
     * both compute {@code float fog = (lViewPos * 3.0 - gl_Fog.start) * gl_Fog.scale;}, which was
     * {@code (lViewPos * 3.0 - 0.0) * 1.0} — an unbounded value rather than the 0..1 linear fog factor the expression
     * is meant to produce — and is now the real {@code (dist - start) / (end - start)}. Verify those two in game.
     */
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

    /** {@code "uniform vec4 iris_FogColor;"} -> {@code "iris_FogColor"}. */
    private static String identifierOf(String declaration) {
        String trimmed = declaration.trim();
        int end = trimmed.lastIndexOf(';');
        int start = trimmed.lastIndexOf(' ', end);
        return trimmed.substring(start + 1, end);
    }

    /** The real attribute that feeds {@code gl_MultiTexCoord0} on the full-screen quad. */
    public static final String FULLSCREEN_TEXCOORD_ATTRIBUTE = "iris_QuadTexCoord";

    private static final Pattern MULTI_TEX_COORD_0 =
            Pattern.compile("(?<![A-Za-z0-9_])gl_MultiTexCoord0(?![A-Za-z0-9_])");

    /**
     * Rewrites {@code gl_MultiTexCoord0} in a full-screen-pass vertex shader to read a real vertex attribute.
     * <p>
     * Composite/deferred/final sources are {@code #version <n> compatibility} and address the quad through the
     * fixed-function built-ins, e.g. Complementary's
     * {@code texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;}. Feeding that built-in by writing generic
     * vertex attribute 8 only works on NVIDIA: the generic-to-conventional aliasing table (8 -&gt;
     * {@code gl_MultiTexCoord0}) comes from {@code NV_vertex_program}. <b>GL guarantees aliasing for attribute 0
     * ({@code gl_Vertex}) and nothing else</b>, and Mesa implements exactly that. On Mesa the built-in therefore kept
     * its default {@code (0,0,0,1)}, every vertex of the quad got {@code texCoord = (0,0)}, and every composite pass
     * sampled a single corner texel across the whole screen — a uniform image whose colour changed as that one texel
     * did. It compiled, linked and drew without a single GL error, which is why nothing in the logs pointed at it.
     * <p>
     * Iris never depends on the aliasing: it binds a real named vertex format and substitutes the built-in
     * ({@code CompositeTransformer}: {@code gl_MultiTexCoord0} -&gt; {@code vec4(UV0, 0.0, 1.0)} plus an injected
     * {@code in vec2 UV0;}). This does the same. Substitution rather than {@code #define} is deliberate — GLSL
     * reserves macro names beginning with {@code gl_}, so defining over the built-in is itself illegal on a strict
     * compiler.
     * <p>
     * Only the full-screen path needs this. Gbuffer programs get {@code gl_MultiTexCoord0} from Minecraft's own
     * fixed-function texture-coordinate arrays, which are the conventional attribute and work everywhere.
     */
    public static String bindFullscreenTexCoord(String vertexSource) {
        if (vertexSource == null || !vertexSource.contains("gl_MultiTexCoord0")) {
            return vertexSource;
        }
        String rewritten = MULTI_TEX_COORD_0.matcher(vertexSource).replaceAll(
                Matcher.quoteReplacement("vec4(" + FULLSCREEN_TEXCOORD_ATTRIBUTE + ", 0.0, 1.0)"));
        return injectAfterPreamble(rewritten, "in vec2 " + FULLSCREEN_TEXCOORD_ATTRIBUTE + ";");
    }

    /**
     * Inserts {@code declarations} immediately before the first line carrying a non-preprocessor token, skipping blank
     * lines, comments and {@code #} directives. GLSL requires every {@code #extension} to precede any real token, so
     * prepending to the top of the body would break the packs that use them.
     */
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
