package com.bdmajora.impetus.umbra.terrain;

import java.util.regex.Matcher;

// The one definition of how gl_Fog.* is substituted, shared by FullscreenTransformer, ImpetusTerrainTransformer
// and ModernPackTransformer so the three cannot drift apart
//
// What the references do: OptiFine 1.12.2 never rewrites gl_Fog at all, because it runs on a GL compatibility
// context where packs read the live fixed-function fog state Minecraft sets in EntityRenderer.setupFog. Iris
// renames gl_Fog to irisInt_Fog and rebuilds it from live uniforms, noting that it must be defined and valid in
// every pass because SEUS v11 reads gl_Fog.color and breaks otherwise. Both hand the pack LIVE values; only
// substituting constants would be wrong
//
// Why per-field substitution rather than Iris's struct: Iris injects a global initialised from uniforms. This port
// was already burned by exactly that pattern once — a uniform-initialised global can be evaluated before the
// uniforms are uploaded, which is why the terrain bridge aliases the matrix built-ins as expressions rather than
// globals. Substituting each field inline is semantically identical and sidesteps the hazard
final class FogParameters {
    // gl_Fog.scale is the one field that cannot be a stand-alone declaration: it is 1.0 / (end - start) computed
    // from two uniforms, so it is neither a constant nor expressible as a const float. It is inlined as an
    // expression instead
    // The max() guard is ours, not Iris's: a zero-width fog range is reachable before the first setupFog, when
    // both uniforms are still 0, and the resulting infinity poisons the pack's entire fog term for that frame
    // An earlier `const float iris_FogScale = 1.0;` here was simply wrong — with this port's fog range (start =
    // far * 0.8, end = far) the correct value is 5 / far, so every pack reading gl_Fog.scale got a fog term off by
    // that factor
    static final String SCALE_EXPRESSION = "(1.0 / max(iris_FogEnd - iris_FogStart, 1e-6))";

    // The uniform declarations the substitution depends on. Injected into every program the rewrite touches, and
    // filled per frame by CommonUniforms
    static final String[] DECLARATIONS = {
            "uniform vec4 iris_FogColor;",
            "uniform float iris_FogDensity;",
            "uniform float iris_FogStart;",
            "uniform float iris_FogEnd;",
    };

    private FogParameters() {
    }

    // Returns source with every gl_Fog.<field> replaced by its live equivalent
    static String rewrite(String source) {
        source = source.replaceAll("\\bgl_Fog\\s*\\.\\s*color\\b", "iris_FogColor");
        source = source.replaceAll("\\bgl_Fog\\s*\\.\\s*density\\b", "iris_FogDensity");
        source = source.replaceAll("\\bgl_Fog\\s*\\.\\s*start\\b", "iris_FogStart");
        source = source.replaceAll("\\bgl_Fog\\s*\\.\\s*end\\b", "iris_FogEnd");
        return source.replaceAll("\\bgl_Fog\\s*\\.\\s*scale\\b", Matcher.quoteReplacement(SCALE_EXPRESSION));
    }
}
