package com.bdmajora.impetus.iris.terrain;

import java.util.regex.Matcher;

/**
 * The single definition of how {@code gl_Fog.*} is substituted, shared by {@link FullscreenTransformer},
 * {@link ImpetusTerrainTransformer} and {@link ModernPackTransformer} so the three cannot drift apart.
 * <p>
 * <strong>Reference behaviour.</strong> OptiFine 1.12.2 never rewrites {@code gl_Fog} at all — it runs on a GL
 * compatibility context, so packs read the live fixed-function fog state Minecraft sets in
 * {@code EntityRenderer.setupFog} ({@code setFogStart(far * 0.25)}, {@code setFogEnd(far)}). Iris renames
 * {@code gl_Fog} to {@code irisInt_Fog} and rebuilds it from live uniforms
 * ({@code CommonTransformer.java:272-287}), with the comment that it "must be defined and valid in all shader
 * passes … SEUS v11 reads gl_Fog.color and breaks if it is not properly defined". Both therefore hand the pack
 * <em>live</em> values; only constants are wrong.
 * <p>
 * <strong>Why per-field substitution rather than Iris's struct.</strong> Iris injects
 * {@code iris_FogParameters irisInt_Fog = iris_FogParameters(iris_FogColor, …);} — a global initialised from
 * uniforms. Impetus already got burned by exactly that pattern once: a uniform-initialised global may be evaluated
 * before the uniforms are uploaded, which is why the terrain bridge aliases the matrix built-ins as expressions
 * rather than globals. Substituting each field inline is semantically identical and sidesteps the hazard entirely.
 * <p>
 * {@code scale} is the one that cannot be a stand-alone declaration: Iris computes {@code 1.0 / (end - start)} from
 * two uniforms, so it is neither a constant nor expressible as {@code const float}. It is inlined as an expression.
 * The previous {@code const float iris_FogScale = 1.0;} was simply wrong — with Impetus's own fog range
 * ({@code CommonUniforms.getFogStart()} = {@code far * 0.8}, {@code getFogEnd()} = {@code far}) the correct value is
 * {@code 5 / far}, so every pack reading {@code gl_Fog.scale} got a fog term off by that factor.
 */
final class FogParameters {
    /**
     * GLSL for {@code gl_Fog.scale}. Iris writes {@code 1.0 / (iris_FogEnd - iris_FogStart)} unguarded; the
     * {@code max} keeps a zero-width fog range (possible before the first {@code setupFog}, when both are 0) from
     * producing an infinity that would poison the pack's whole fog term.
     */
    static final String SCALE_EXPRESSION = "(1.0 / max(iris_FogEnd - iris_FogStart, 1e-6))";

    /** The uniform declarations the substitution depends on; fed per frame by {@code CommonUniforms}. */
    static final String[] DECLARATIONS = {
            "uniform vec4 iris_FogColor;",
            "uniform float iris_FogDensity;",
            "uniform float iris_FogStart;",
            "uniform float iris_FogEnd;",
    };

    private FogParameters() {
    }

    /** {@return {@code source} with every {@code gl_Fog.<field>} replaced by its live equivalent} */
    static String rewrite(String source) {
        source = source.replaceAll("\\bgl_Fog\\s*\\.\\s*color\\b", "iris_FogColor");
        source = source.replaceAll("\\bgl_Fog\\s*\\.\\s*density\\b", "iris_FogDensity");
        source = source.replaceAll("\\bgl_Fog\\s*\\.\\s*start\\b", "iris_FogStart");
        source = source.replaceAll("\\bgl_Fog\\s*\\.\\s*end\\b", "iris_FogEnd");
        return source.replaceAll("\\bgl_Fog\\s*\\.\\s*scale\\b", Matcher.quoteReplacement(SCALE_EXPRESSION));
    }
}
