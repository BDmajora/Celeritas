package org.taumc.celeritas.iris.gl.shader;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The OptiFine {@code MC_*} preprocessor macros injected into every shader stage.
 * <p>
 * 1.12.2 packs branch on these (e.g. {@code #ifdef MC_GL_VENDOR_NVIDIA}, {@code #if MC_VERSION >= 11202}); a pack that
 * does not find them simply takes its {@code #else} path, so the set here is intentionally conservative. The
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
