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

    private static String rewriteFogParameters(String source) {
        source = source.replaceAll("\\bgl_Fog\\s*\\.\\s*color\\b", "vec4(0.0)");
        source = source.replaceAll("\\bgl_Fog\\s*\\.\\s*density\\b", "0.0");
        source = source.replaceAll("\\bgl_Fog\\s*\\.\\s*start\\b", "0.0");
        source = source.replaceAll("\\bgl_Fog\\s*\\.\\s*end\\b", "1.0");
        return source.replaceAll("\\bgl_Fog\\s*\\.\\s*scale\\b", "1.0");
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
