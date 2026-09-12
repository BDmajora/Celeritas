package com.bdmajora.impetus.umbra.terrain;

import java.util.regex.Matcher;

// The one definition of how gl_Fog.* is substituted, shared by all three transformers so they cannot drift; each field is inlined rather than a uniform-initialised global, which this port has seen evaluated before upload
final class FogParameters {
    // gl_Fog.scale is 1.0 / (end - start) from two uniforms, so it is inlined as an expression; the max() guard is ours, since a zero-width range before the first setupFog yields infinity, and an earlier `const 1.0` was off by 5 / far for every pack reading it
    static final String SCALE_EXPRESSION = "(1.0 / max(iris_FogEnd - iris_FogStart, 1e-6))";

    // The uniform declarations the substitution depends on, injected into every program the rewrite touches and filled per frame by CommonUniforms
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
