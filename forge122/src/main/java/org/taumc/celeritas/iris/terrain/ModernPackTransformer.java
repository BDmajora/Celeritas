package org.taumc.celeritas.iris.terrain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately minimal transform for <em>modern</em> shader packs (Complementary, BSL, …) — the ones written as
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
 * <b>Phase 1 of the modern-pack port.</b> This gets the fullscreen passes (composite/deferred/final) to preprocess and
 * compile against the pack's own option defaults; the option-override menu, the Embeddium terrain vertex bridge, and
 * the extra uniforms are later phases.
 */
public final class ModernPackTransformer {
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#version\\s+(\\d+)(?:\\s+\\w+)?\\s*$");

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
        Matcher matcher = VERSION.matcher(source);
        if (matcher.find()) {
            return source.substring(0, matcher.start())
                    + "#version 330 compatibility"
                    + source.substring(matcher.end());
        }
        // No #version at all — prepend one so the driver doesn't default to 110.
        return "#version 330 compatibility\n" + source;
    }
}
