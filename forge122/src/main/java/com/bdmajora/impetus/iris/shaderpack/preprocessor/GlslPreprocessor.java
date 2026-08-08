package com.bdmajora.impetus.iris.shaderpack.preprocessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Light-touch GLSL source transformation applied after {@code #include} flattening and before GL compilation.
 * <p>
 * Responsibilities handled here (Phase 1):
 * <ul>
 *     <li>Locating the {@code #version} directive (which GLSL requires to be the first non-comment, non-blank line)
 *         and exposing the declared version.</li>
 *     <li>Injecting {@code #define} macros (shader pack options + pipeline feature flags) immediately after the
 *         {@code #version} line, where they are legal.</li>
 *     <li>Injecting a default {@code #version} when a pack omits one (1.12.2 packs commonly target {@code 120}).</li>
 * </ul>
 * <p>
 * Legacy fixed-function built-in substitution ({@code gl_MultiTexCoord0}, {@code gl_Color}, {@code gl_Normal}, ...)
 * into explicit vertex attributes is intentionally <em>not</em> performed here: it depends on the concrete attribute
 * bindings established by the GL program layer and is implemented in a later phase. {@link #replaceLegacyBuiltins} is
 * provided as the documented seam for that work.
 */
public final class GlslPreprocessor {
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\s*#version\\s+(\\d+)(?:\\s+(\\w+))?.*$");

    /** Default GLSL version assumed for 1.12.2-era packs that omit a {@code #version} directive. */
    public static final int DEFAULT_VERSION = 120;

    private GlslPreprocessor() {
    }

    /**
     * @return the GLSL version number declared by the first {@code #version} directive, or {@code -1} if none.
     */
    public static int detectVersion(List<String> lines) {
        for (String line : lines) {
            Matcher m = VERSION_PATTERN.matcher(line);
            if (m.matches()) {
                return Integer.parseInt(m.group(1));
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }
            // First meaningful line wasn't #version; GLSL will assume 110 but packs rely on 120 built-ins.
            return -1;
        }
        return -1;
    }

    /**
     * Inserts the given macro definitions immediately after the {@code #version} line (inserting a default
     * {@code #version} first if the source has none), returning the rewritten source lines.
     *
     * @param defines ordered macro name -> value; an empty value yields a bare {@code #define NAME}.
     */
    public static List<String> injectDefines(List<String> lines, Map<String, String> defines) {
        List<String> out = new ArrayList<>(lines.size() + defines.size() + 1);

        int versionIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (VERSION_PATTERN.matcher(lines.get(i)).matches()) {
                versionIndex = i;
                break;
            }
        }

        if (versionIndex < 0) {
            // No #version present: prepend a default one, then defines, then the original source.
            out.add("#version " + DEFAULT_VERSION);
            out.addAll(toDefineLines(defines));
            out.addAll(lines);
            return out;
        }

        out.add(lines.get(versionIndex));
        out.addAll(toDefineLines(defines));
        for (int i = 0; i < lines.size(); i++) {
            if (i == versionIndex || VERSION_PATTERN.matcher(lines.get(i)).matches()) {
                continue;
            }
            out.add(lines.get(i));
        }
        return out;
    }

    private static List<String> toDefineLines(Map<String, String> defines) {
        List<String> result = new ArrayList<>(defines.size());
        for (Map.Entry<String, String> e : defines.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                result.add("#define " + e.getKey());
            } else {
                result.add("#define " + e.getKey() + " " + e.getValue());
            }
        }
        return result;
    }

    /**
     * Drops the branches of {@code #if}/{@code #ifdef} conditionals the given macro set does not take, so a scanner
     * sees only the declarations the GPU will actually compile. <b>For directive extraction only</b> — the result is
     * not compilable source (every {@code #} line, {@code #version} and {@code #extension} included, is consumed).
     * <p>
     * Iris scans source that JCPP has already preprocessed ({@code ShaderPack}'s source provider feeds
     * {@code ProgramSet}, which runs {@code ConstDirectiveParser} over it), so a directive sitting in a dead branch is
     * simply not there. A raw-text regex instead takes the first textual match, which happily reads a value out of a
     * branch the pack disabled.
     * <p>
     * Resolve one stage at a time: define state and {@code #if} nesting must not leak from one file into the next, and
     * packs select which half of a shared body to compile with a {@code #define VERTEX_SHADER}/{@code FRAGMENT_SHADER}
     * at the top of each entry point. Note that resolving consumes the {@code #define} lines it evaluates, so
     * {@code #define}-form directives ({@code SHADOWRES}, {@code SHADOWFOV}) must still be read from the raw source.
     *
     * @return the resolved source, or {@code source} unchanged if the conditionals could not be evaluated
     */
    public static String resolveConditionals(String source, Map<String, String> defines) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        try {
            String resolved = PropertiesPreprocessor.preprocess(source, defines);
            // An unterminated #if can swallow the rest of the file; keep the raw source rather than scan nothing.
            return resolved.trim().isEmpty() ? source : resolved;
        } catch (RuntimeException e) {
            return source;
        }
    }

    /**
     * Seam for later phases: rewrite legacy fixed-function built-ins to explicit {@code in}/attribute names.
     * Because Impetus renders chunks through VAOs, the fixed-function attribute slots are never populated, so a
     * GLSL-150+ translation needs to map e.g. {@code gl_MultiTexCoord0 -> vec4(mc_midTexCoord, 0.0, 1.0)}.
     * Not implemented in Phase 1; returns the input unchanged.
     */
    public static List<String> replaceLegacyBuiltins(List<String> lines, Map<String, String> attributeBindings) {
        // Intentionally a no-op for Phase 1. Kept as an explicit extension point.
        return lines;
    }

    /** Convenience: a stable, insertion-ordered map suitable for {@link #injectDefines}. */
    public static Map<String, String> newDefineMap() {
        return new LinkedHashMap<>();
    }
}
