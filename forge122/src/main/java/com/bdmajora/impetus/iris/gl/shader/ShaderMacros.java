package com.bdmajora.impetus.iris.gl.shader;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The OptiFine {@code MC_*} preprocessor macros injected into every shader stage.
 * <p>
 * 1.12.2 packs branch on these (e.g. {@code #ifdef MC_GL_VENDOR_NVIDIA}, {@code #if MC_VERSION >= 11202}). The
 * GL-dependent vendor/renderer macros require a live context and are added via {@link #withGlInfo} once the render
 * thread is up; {@link #standard()} holds everything computable without GL.
 */
public final class ShaderMacros {
    /** Minecraft version encoded the OptiFine way: 1.12.2 -> 11202. */
    public static final int MC_VERSION = 11202;

    private ShaderMacros() {
    }

    /** Macros that need no GL context (Minecraft version, host OS, baseline quality knobs). */
    public static Map<String, String> standard() {
        Map<String, String> macros = new LinkedHashMap<>();
        macros.put("MC_VERSION", Integer.toString(MC_VERSION));
        macros.put(osMacro(), "");
        macros.put("MC_RENDER_QUALITY", "1.0");
        macros.put("MC_SHADOW_QUALITY", "1.0");
        macros.put("MC_HAND_DEPTH", "0.125");
        // Iris feature flags for the subset of Iris extensions this port implements. Packs declare what they can
        // use via `iris.features.optional` and gate on `#ifdef IRIS_FEATURE_<NAME>` (Complementary gates its
        // colored lighting on CUSTOM_IMAGES).
        macros.put("IRIS_FEATURE_CUSTOM_IMAGES", "");
        // Iris identity define: packs gate their Iris-exclusive uniform DECLARATIONS on this (Complementary's
        // uniforms.glsl declares renderStage/is_invisible behind #ifdef IS_IRIS). Everything that block declares at
        // MC_VERSION 11202 is provided by CommonUniforms.
        macros.put("IS_IRIS", "");
        // Iris version, encoded major*10000 + minor*100 + bugfix (StandardMacros.getFormattedIrisVersion). Packs gate
        // real behavior on this: Complementary's common.glsl takes `cameraPositionBestFract = cameraPositionFract`
        // (the precise double-derived split we now upload) at `IRIS_VERSION >= 10800`, instead of the OptiFine
        // `fract(cameraPosition)` path whose float precision loss makes the colored-lighting voxel grid — and thus
        // block-edge lighting — shimmer at world coordinates far from origin. 10805 is the lowest value that both
        // enables that path AND leaves every legacy `IRIS_VERSION < N` workaround exactly where undefined(=0) left it
        // (the 10800..10804 skybasic moon-discard stays off; the <10902 skytextured sun fallback stays on — that
        // geometric fallback is more reliable than our still-partial fixed-function renderStage mapping).
        macros.put("IRIS_VERSION", "10805");
        // Iris render-stage constants (WorldRenderingPhase ordinals, exact Iris order) for the renderStage uniform.
        macros.put("MC_RENDER_STAGE_NONE", "0");
        macros.put("MC_RENDER_STAGE_SKY", "1");
        macros.put("MC_RENDER_STAGE_SUNSET", "2");
        macros.put("MC_RENDER_STAGE_CUSTOM_SKY", "3");
        macros.put("MC_RENDER_STAGE_SUN", "4");
        macros.put("MC_RENDER_STAGE_MOON", "5");
        macros.put("MC_RENDER_STAGE_STARS", "6");
        macros.put("MC_RENDER_STAGE_VOID", "7");
        macros.put("MC_RENDER_STAGE_TERRAIN_SOLID", "8");
        macros.put("MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED", "9");
        macros.put("MC_RENDER_STAGE_TERRAIN_CUTOUT", "10");
        macros.put("MC_RENDER_STAGE_ENTITIES", "11");
        macros.put("MC_RENDER_STAGE_BLOCK_ENTITIES", "12");
        macros.put("MC_RENDER_STAGE_DESTROY", "13");
        macros.put("MC_RENDER_STAGE_OUTLINE", "14");
        macros.put("MC_RENDER_STAGE_DEBUG", "15");
        macros.put("MC_RENDER_STAGE_HAND_SOLID", "16");
        macros.put("MC_RENDER_STAGE_TERRAIN_TRANSLUCENT", "17");
        macros.put("MC_RENDER_STAGE_TRIPWIRE", "18");
        macros.put("MC_RENDER_STAGE_PARTICLES", "19");
        macros.put("MC_RENDER_STAGE_CLOUDS", "20");
        macros.put("MC_RENDER_STAGE_RAIN_SNOW", "21");
        macros.put("MC_RENDER_STAGE_WORLD_BORDER", "22");
        macros.put("MC_RENDER_STAGE_HAND_TRANSLUCENT", "23");
        return macros;
    }

    /**
     * Adds the GL-context-dependent macros (GLSL version and vendor/renderer family) to an existing macro map.
     *
     * @param glslVersion the GLSL version the pipeline compiles against (e.g. 120)
     * @param glVersion   the GL version as an integer (e.g. 320 for 3.2)
     * @param vendor      the {@code GL_VENDOR} string
     * @param renderer    the {@code GL_RENDERER} string
     */
    public static void withGlInfo(Map<String, String> macros, int glslVersion, int glVersion, String vendor, String renderer) {
        macros.put("MC_GL_VERSION", Integer.toString(glVersion));
        macros.put("MC_GLSL_VERSION", Integer.toString(glslVersion));

        String vendorMacro = vendorMacro(vendor);
        if (vendorMacro != null) {
            macros.put(vendorMacro, "");
        }
        String rendererMacro = rendererMacro(renderer);
        if (rendererMacro != null) {
            macros.put(rendererMacro, "");
        }
    }

    /**
     * Inserts {@code #define} lines for the given macros right after the {@code #version} directive (or at the top
     * when there is none). Used by the fullscreen/terrain/compute paths, which do not go through
     * {@code ShaderProgramCompiler}'s define application.
     */
    public static String injectDefines(String source, Map<String, String> macros) {
        StringBuilder defines = new StringBuilder();
        for (Map.Entry<String, String> macro : macros.entrySet()) {
            defines.append("#define ").append(macro.getKey());
            if (!macro.getValue().isEmpty()) {
                defines.append(' ').append(macro.getValue());
            }
            defines.append('\n');
        }
        int versionStart = source.indexOf("#version");
        if (versionStart < 0) {
            return defines + source;
        }
        int lineEnd = source.indexOf('\n', versionStart);
        if (lineEnd < 0) {
            return source + "\n" + defines;
        }
        return source.substring(0, lineEnd + 1) + defines + source.substring(lineEnd + 1);
    }

    private static String osMacro() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "MC_OS_WINDOWS";
        } else if (os.contains("mac") || os.contains("darwin")) {
            return "MC_OS_MAC";
        } else if (os.contains("nux") || os.contains("nix")) {
            return "MC_OS_LINUX";
        }
        return "MC_OS_OTHER";
    }

    private static String vendorMacro(String vendor) {
        if (vendor == null) {
            return "MC_GL_VENDOR_OTHER";
        }
        String v = vendor.toLowerCase(Locale.ROOT);
        if (v.startsWith("ati") || v.contains("amd")) {
            return "MC_GL_VENDOR_ATI";
        } else if (v.startsWith("intel")) {
            return "MC_GL_VENDOR_INTEL";
        } else if (v.startsWith("nvidia")) {
            return "MC_GL_VENDOR_NVIDIA";
        } else if (v.startsWith("x.org")) {
            return "MC_GL_VENDOR_XORG";
        }
        return "MC_GL_VENDOR_OTHER";
    }

    private static String rendererMacro(String renderer) {
        if (renderer == null) {
            return "MC_GL_RENDERER_OTHER";
        }
        String r = renderer.toLowerCase(Locale.ROOT);
        if (r.contains("radeon") || r.contains("amd")) {
            return "MC_GL_RENDERER_RADEON";
        } else if (r.contains("geforce")) {
            return "MC_GL_RENDERER_GEFORCE";
        } else if (r.contains("quadro")) {
            return "MC_GL_RENDERER_QUADRO";
        } else if (r.contains("intel")) {
            return "MC_GL_RENDERER_INTEL";
        } else if (r.contains("gallium")) {
            return "MC_GL_RENDERER_GALLIUM";
        } else if (r.contains("mesa")) {
            return "MC_GL_RENDERER_MESA";
        }
        return "MC_GL_RENDERER_OTHER";
    }
}
