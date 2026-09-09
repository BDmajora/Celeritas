package com.bdmajora.impetus.umbra.gl.shader;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

// OptiFine MC_* preprocessor macros injected into every shader stage
// GL-dependent vendor/renderer macros need a live context and go through withGlInfo once the render thread
// is up; standard() holds everything else computable without GL
public final class ShaderMacros {
    // Minecraft version encoded the OptiFine way: 1.12.2 -> 11202
    public static final int MC_VERSION = 11202;

    // Programs forced to compile as plain OptiFine 1.12.2 (IS_IRIS/IRIS_VERSION withheld) so they take the
    // path they authored for this MC version instead of the Umbra path. Sildur's gbuffers_water/composite1 pair
    // must be listed together — one writes the wave normal to gl_FragData[2] (DRAWBUFFERS:412), the other reads
    // it from colortex2 — but setPackLegacyPrograms detects that pairing automatically from the pack's own guards.
    // This set is only the manual override via -Dimpetus.umbra.legacyPrograms=...; empty by default.
    private static final java.util.Set<String> FORCED_LEGACY_PROGRAMS = parseLegacyPrograms();

    // Programs the loaded pack itself marked as having an authored pre-Umbra path (see ShaderPack.detectLegacyPrograms)
    // Replaced on every pack load, so must not be final
    private static volatile java.util.Set<String> packLegacyPrograms = java.util.Collections.emptySet();

    // Installs the pack-detected legacy program list; called once per pack load before any program compiles
    // so the terrain and composite compile paths see the same set
    public static void setPackLegacyPrograms(java.util.Set<String> names) {
        java.util.Set<String> lowered = new java.util.HashSet<>();
        for (String name : names) {
            lowered.add(name.toLowerCase(Locale.ROOT));
        }
        packLegacyPrograms = lowered;
    }

    private ShaderMacros() {
    }

    private static java.util.Set<String> parseLegacyPrograms() {
        String value = System.getProperty("impetus.umbra.legacyPrograms", "");
        java.util.Set<String> names = new java.util.HashSet<>();
        for (String name : value.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    // Macro set programName compiles against — identical to input unless the program is a legacy program, in
    // which case the Umbra identity macros are withheld. Callers MUST use this same returned map for both
    // DrawBuffers.parseActive and injectDefines on the same program, since the two branches disagree on
    // DRAWBUFFERS and mixing them points a gl_FragData write at an unbound slot.
    public static Map<String, String> forProgram(Map<String, String> macros, String programName) {
        if (programName == null) {
            return macros;
        }
        String key = programName.toLowerCase(Locale.ROOT);
        if (!FORCED_LEGACY_PROGRAMS.contains(key) && !packLegacyPrograms.contains(key)) {
            return macros;
        }
        Map<String, String> scoped = new LinkedHashMap<>(macros);
        scoped.remove("IS_IRIS");
        scoped.remove("IRIS_VERSION");
        return scoped;
    }

    // Macros that need no GL context (Minecraft version, host OS, baseline quality knobs)
    public static Map<String, String> standard() {
        Map<String, String> macros = new LinkedHashMap<>();
        macros.put("MC_VERSION", Integer.toString(MC_VERSION));
        macros.put(osMacro(), "");
        macros.put("MC_RENDER_QUALITY", "1.0");
        macros.put("MC_SHADOW_QUALITY", "1.0");
        macros.put("MC_HAND_DEPTH", "0.125");
        // PBR sampler availability (OptiFine semantics: defined when the normal/specular map feature is enabled,
        // which is OptiFine's default-on). The `normals`/`specular` samplers are always bound — either the
        // stitched _n/_s companion atlases or the neutral 1×1 defaults — so sampling them is always well-defined.
        macros.put("MC_NORMAL_MAP", "");
        macros.put("MC_SPECULAR_MAP", "");
        // Resource-pack-declared PBR texture format (assets/minecraft/optifine/texture.properties `format=`),
        // e.g. MC_TEXTURE_FORMAT_LAB_PBR + MC_TEXTURE_FORMAT_LAB_PBR_1_3. Umbra parity.
        com.bdmajora.impetus.umbra.pbr.TextureFormatLoader.addFormatMacros(macros);
        // Feature flags for the subset of the Iris extensions this port implements, one IRIS_FEATURE_<NAME>
        // define each. Packs gate on `#ifdef IRIS_FEATURE_<NAME>` — Complementary's colored lighting hangs off
        // IRIS_FEATURE_CUSTOM_IMAGES, and without that define its shadowcomp pass never declares voxel_sampler.
        com.bdmajora.impetus.umbra.features.FeatureFlags.addUsableDefines(macros);
        // The three names below (IS_IRIS, IRIS_VERSION, and the IRIS_FEATURE_* prefix above) are what shader packs
        // read to tell an Iris-class pipeline from plain OptiFine. They are the pack-facing contract, NOT our own
        // naming: this subsystem is called Umbra everywhere else, but renaming these makes every pack fall back to
        // its OptiFine path — Complementary puts up its "Colored Lighting is not supported on Optifine" screen and
        // drops colored lighting entirely. Leave them spelled exactly as Iris spells them.
        // IS_IRIS specifically gates the pack's Iris-exclusive uniform DECLARATIONS (Complementary's uniforms.glsl
        // declares renderStage/is_invisible behind it); everything that block declares at MC_VERSION 11202 is
        // uploaded by CommonUniforms, so claiming it is honest.
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
        // Umbra render-stage constants (WorldRenderingPhase ordinals, exact Umbra order) for the renderStage uniform.
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

    // Adds the GL-context-dependent macros (GLSL version and vendor/renderer family) to an existing macro map
    public static void withGlInfo(Map<String, String> macros, int glslVersion, int glVersion, String vendor, String renderer) {
        macros.put("MC_GL_VERSION", Integer.toString(glVersion));
        macros.put("MC_GLSL_VERSION", Integer.toString(glslVersion));
        withGpuIdentity(macros, vendor, renderer);
    }

    // Adds only the vendor/renderer identity macros (MC_GL_VENDOR_*, MC_GL_RENDERER_*), which packs use to gate
    // hardware workarounds (Clarity's NVIDIA immut, Photon/Solas Intel paths, Complementary's AMD path).
    // Deliberately skips MC_GL_VERSION/MC_GLSL_VERSION — this pipeline compiles individual programs at 120,
    // 330 or 460 depending on path, so advertising the real driver version would invite GLSL-120 programs
    // to use syntax they can't take.
    public static void withGpuIdentity(Map<String, String> macros, String vendor, String renderer) {
        String vendorMacro = vendorMacro(vendor);
        if (vendorMacro != null) {
            macros.put(vendorMacro, "");
        }
        String rendererMacro = rendererMacro(renderer);
        if (rendererMacro != null) {
            macros.put(rendererMacro, "");
        }
    }

    // Inserts #define lines right after the #version directive (or at the top if there is none)
    // Used by the fullscreen/terrain/compute paths, which skip ShaderProgramCompiler's define application
    public static String injectDefines(String source, Map<String, String> macros) {
        StringBuilder defines = new StringBuilder();
        for (Map.Entry<String, String> macro : macros.entrySet()) {
            defines.append("#define ").append(macro.getKey());
            if (!macro.getValue().isEmpty()) {
                defines.append(' ').append(macro.getValue());
            }
            defines.append('\n');
        }
        String[] lines = source.split("\n", -1);
        int versionIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("#version")) {
                versionIndex = i;
                break;
            }
        }
        if (versionIndex < 0) {
            return defines + source;
        }
        StringBuilder out = new StringBuilder(source.length() + defines.length());
        out.append(lines[versionIndex]).append('\n').append(defines);
        for (int i = 0; i < lines.length; i++) {
            if (i == versionIndex || lines[i].trim().startsWith("#version")) {
                continue;
            }
            out.append(lines[i]);
            if (i + 1 < lines.length) {
                out.append('\n');
            }
        }
        return out.toString();
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

    // Matches Umbra StandardMacros.getVendor() exactly (which matches OptiFine's documented behaviour)
    // Prefix tests, not substring — the distinction matters, see rendererMacro below
    private static String vendorMacro(String vendor) {
        if (vendor == null) {
            return "MC_GL_VENDOR_OTHER";
        }
        String v = vendor.toLowerCase(Locale.ROOT);
        if (v.startsWith("ati")) {
            return "MC_GL_VENDOR_ATI";
        } else if (v.startsWith("intel")) {
            return "MC_GL_VENDOR_INTEL";
        } else if (v.startsWith("nvidia")) {
            return "MC_GL_VENDOR_NVIDIA";
        } else if (v.startsWith("amd")) {
            // Umbra reports AMD separately from ATI. Folding it into ATI still satisfied Complementary's
            // `#if defined MC_GL_VENDOR_AMD || defined MC_GL_VENDOR_ATI`, but only by luck — a pack testing
            // MC_GL_VENDOR_AMD alone would have silently taken the wrong branch.
            return "MC_GL_VENDOR_AMD";
        } else if (v.startsWith("x.org")) {
            return "MC_GL_VENDOR_XORG";
        }
        return "MC_GL_VENDOR_OTHER";
    }

    // Matches Umbra StandardMacros.getRenderer() exactly, including test order.
    // Must be prefix tests, not substring — contains("intel") used to misfire on Mesa Intel Arc strings
    // ("Mesa Intel(R) Arc(tm) B580...") and match MC_GL_RENDERER_INTEL before the mesa branch, silently
    // advertising modern Arc hardware as an ancient Intel iGPU and pushing it down degraded pack paths.
    private static String rendererMacro(String renderer) {
        if (renderer == null) {
            return "MC_GL_RENDERER_OTHER";
        }
        String r = renderer.toLowerCase(Locale.ROOT);
        if (r.startsWith("amd") || r.startsWith("ati") || r.startsWith("radeon")) {
            return "MC_GL_RENDERER_RADEON";
        } else if (r.startsWith("gallium")) {
            return "MC_GL_RENDERER_GALLIUM";
        } else if (r.startsWith("intel")) {
            return "MC_GL_RENDERER_INTEL";
        } else if (r.startsWith("geforce") || r.startsWith("nvidia")) {
            return "MC_GL_RENDERER_GEFORCE";
        } else if (r.startsWith("quadro") || r.startsWith("nvs")) {
            return "MC_GL_RENDERER_QUADRO";
        } else if (r.startsWith("mesa")) {
            return "MC_GL_RENDERER_MESA";
        } else if (r.startsWith("apple")) {
            return "MC_GL_RENDERER_APPLE";
        }
        return "MC_GL_RENDERER_OTHER";
    }
}
