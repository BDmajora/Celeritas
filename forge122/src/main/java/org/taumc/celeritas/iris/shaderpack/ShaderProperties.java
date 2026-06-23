package org.taumc.celeritas.iris.shaderpack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A parsed {@code shaders.properties} file (OptiFine format).
 * <p>
 * 1.12.2 packs use the classic OptiFine directives. Rather than enumerate every possible key, this parser keeps the
 * full raw key/value map and layers typed accessors over the handful of directives the pipeline actually consumes
 * (shadow configuration, cloud mode, per-program blend modes, per-program enable toggles). Unknown keys are preserved
 * verbatim so later phases can read them without re-parsing.
 * <p>
 * Parsing is deliberately a simple split-on-first-{@code =}: it avoids {@link java.util.Properties}' backslash-escape
 * surprises, which matter because GLSL-adjacent values occasionally contain {@code \}.
 */
public final class ShaderProperties {
    private final Map<String, String> raw;

    private ShaderProperties(Map<String, String> raw) {
        this.raw = raw;
    }

    public static ShaderProperties empty() {
        return new ShaderProperties(Collections.emptyMap());
    }

    public static ShaderProperties parse(String contents) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String rawLine : contents.split("\r\n|\r|\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '!') {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!key.isEmpty()) {
                map.put(key, value);
            }
        }
        return new ShaderProperties(map);
    }

    /** @return the raw, unmodifiable directive map in declaration order. */
    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(this.raw);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(this.raw.get(key));
    }

    // --- Typed pipeline-relevant accessors ---

    /** {@code shadowMapResolution} — square shadow map size in texels, if specified. */
    public OptionalInt getShadowMapResolution() {
        return getInt("shadowMapResolution");
    }

    /** {@code shadowDistance} — shadow render distance in blocks, if specified. */
    public OptionalInt getShadowDistance() {
        return getInt("shadowDistance");
    }

    /** {@code shadow.enabled} — explicit shadow toggle, if specified. */
    public Optional<Boolean> getShadowEnabled() {
        return getBoolean("shadow.enabled");
    }

    /** {@code clouds} = off | fast | fancy, if specified. */
    public Optional<String> getCloudMode() {
        return get("clouds").map(s -> s.toLowerCase(Locale.ROOT));
    }

    /**
     * Per-program blend override, e.g. {@code blend.composite2 = SRC_ALPHA ONE_MINUS_SRC_ALPHA} or {@code blend.water = off}.
     *
     * @param programName the program source name, e.g. {@code composite2}
     */
    public Optional<String> getBlendModeOverride(String programName) {
        return get("blend." + programName);
    }

    /**
     * Per-program enable toggle, e.g. {@code program.composite4.enabled = false}.
     */
    public Optional<Boolean> getProgramEnabled(String programName) {
        return getBoolean("program." + programName + ".enabled");
    }

    private OptionalInt getInt(String key) {
        String value = this.raw.get(key);
        if (value == null) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private Optional<Boolean> getBoolean(String key) {
        String value = this.raw.get(key);
        if (value == null) {
            return Optional.empty();
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true")) {
            return Optional.of(Boolean.TRUE);
        }
        if (v.equals("false")) {
            return Optional.of(Boolean.FALSE);
        }
        return Optional.empty();
    }
}
