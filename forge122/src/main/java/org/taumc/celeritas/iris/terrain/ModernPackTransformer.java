package org.taumc.celeritas.iris.terrain;

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
                    + "#version " + targetVersion(source) + " compatibility"
                    + source.substring(matcher.end());
        }
        // No #version at all — prepend one so the driver doesn't default to 110.
        return "#version " + targetVersion(source) + " compatibility\n" + source;
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
}
