package org.taumc.celeritas.iris.shaderpack;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.taumc.celeritas.iris.shaderpack.texture.TextureStage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final Logger LOGGER = LogManager.getLogger("Celeritas/Iris");

    private final Map<String, String> raw;

    // --- Option-menu layout directives (parsed from the raw, non-preprocessed file) ---
    private final List<String> sliderOptions = new ArrayList<>();
    private final Map<String, List<String>> profiles = new LinkedHashMap<>();
    private List<String> mainScreenOptions = null;
    private final Map<String, List<String>> subScreenOptions = new LinkedHashMap<>();
    private Integer mainScreenColumnCount = null;
    private final Map<String, Integer> subScreenColumnCount = new HashMap<>();

    // --- Custom texture directives (Iris ShaderProperties parity) ---
    /** {@code texture.noise = <path>} — replaces the generated noisetex. */
    private String noiseTexturePath = null;
    /** {@code texture.<stage>.<sampler> = <path>} — per-stage sampler overrides, keyed by stage then sampler name. */
    private final Map<TextureStage, Map<String, String>> customTextures = new EnumMap<>(TextureStage.class);
    /** {@code customTexture.<name> = <path>} — pack-defined named samplers, bound in every stage. */
    private final Map<String, String> irisCustomTextures = new LinkedHashMap<>();

    private ShaderProperties(Map<String, String> raw) {
        this.raw = raw;
        parseMenuDirectives();
        parseCustomTextureDirectives();
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

    /**
     * Parses the option-menu layout directives ({@code sliders}, {@code profile.*}, {@code screen}, {@code screen.*},
     * {@code screen.columns}, {@code screen.*.columns}) from the raw map. Mirrors Iris's ShaderProperties handling.
     */
    private void parseMenuDirectives() {
        this.raw.forEach((key, value) -> {
            if (key.equals("sliders")) {
                this.sliderOptions.clear();
                this.sliderOptions.addAll(splitWhitespace(value));
            } else if (key.startsWith("profile.")) {
                this.profiles.put(key.substring("profile.".length()), splitWhitespace(value));
            } else if (key.equals("screen.columns")) {
                this.mainScreenColumnCount = parseIntOrNull(value);
            } else if (key.startsWith("screen.") && key.endsWith(".columns")) {
                String name = key.substring("screen.".length(), key.length() - ".columns".length());
                Integer columns = parseIntOrNull(value);
                if (columns != null) {
                    this.subScreenColumnCount.put(name, columns);
                }
            } else if (key.equals("screen")) {
                this.mainScreenOptions = splitWhitespace(value);
            } else if (key.startsWith("screen.")) {
                this.subScreenOptions.put(key.substring("screen.".length()), splitWhitespace(value));
            }
        });
    }

    /**
     * Parses the custom-texture directives, mirroring Iris's {@code ShaderProperties} handling:
     * <ul>
     * <li>{@code texture.noise = <path>};</li>
     * <li>{@code texture.<stage>.<sampler> = <path>} — the sampler segment may carry a {@code .N} suffix (OptiFine
     * mip-level syntax); like Iris, only the base name before the first {@code .} is kept;</li>
     * <li>{@code customTexture.<name> = <path>} — Iris-exclusive named samplers, available in every stage.</li>
     * </ul>
     * Multi-token values are Iris raw-texture definitions ({@code <path> <type> <format> ...}); those are logged and
     * skipped — no OptiFine-format 1.12.2 pack uses them.
     */
    private void parseCustomTextureDirectives() {
        this.raw.forEach((key, value) -> {
            if (key.equals("texture.noise")) {
                this.noiseTexturePath = value;
            } else if (key.startsWith("texture.")) {
                String rest = key.substring("texture.".length());
                int dot = rest.indexOf('.');
                if (dot <= 0 || dot == rest.length() - 1) {
                    LOGGER.warn("[Iris] Malformed custom texture directive, ignoring: {}", key);
                    return;
                }
                String stageName = rest.substring(0, dot);
                // OptiFine allows a trailing ".N" mip-level suffix; Iris keeps only the base sampler name.
                String samplerName = rest.substring(dot + 1).split("\\.")[0];
                Optional<TextureStage> stage = TextureStage.parse(stageName);
                if (!stage.isPresent()) {
                    LOGGER.warn("[Iris] Unknown texture stage \"{}\", ignoring custom texture directive for {}",
                            stageName, key);
                    return;
                }
                if (value.trim().split("\\s+").length > 1) {
                    LOGGER.warn("[Iris] Raw custom texture definitions are not supported, ignoring: {} = {}", key, value);
                    return;
                }
                this.customTextures
                        .computeIfAbsent(stage.get(), s -> new LinkedHashMap<>())
                        .put(samplerName, value);
            } else if (key.startsWith("customTexture.")) {
                String name = key.substring("customTexture.".length());
                if (name.isEmpty()) {
                    LOGGER.warn("[Iris] Malformed custom texture directive, ignoring: {}", key);
                    return;
                }
                if (value.trim().split("\\s+").length > 1) {
                    LOGGER.warn("[Iris] Raw custom texture definitions are not supported, ignoring: {} = {}", key, value);
                    return;
                }
                this.irisCustomTextures.put(name, value.trim());
            }
        });
    }

    private static List<String> splitWhitespace(String value) {
        List<String> result = new ArrayList<>();
        for (String token : value.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                result.add(token);
            }
        }
        return result;
    }

    private static Integer parseIntOrNull(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** @return the raw, unmodifiable directive map in declaration order. */
    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(this.raw);
    }

    /** Option names that should be rendered as sliders (cycling through allowed values) rather than click-to-cycle. */
    public List<String> getSliderOptions() {
        return Collections.unmodifiableList(this.sliderOptions);
    }

    /** Declared profiles, in declaration order, each mapping to its list of option directives. */
    public Map<String, List<String>> getProfiles() {
        return Collections.unmodifiableMap(this.profiles);
    }

    /** The main option screen's element layout, if the pack declares one. */
    public Optional<List<String>> getMainScreenOptions() {
        return Optional.ofNullable(this.mainScreenOptions);
    }

    /** Sub-screen layouts keyed by sub-screen name. */
    public Map<String, List<String>> getSubScreenOptions() {
        return Collections.unmodifiableMap(this.subScreenOptions);
    }

    public Optional<Integer> getMainScreenColumnCount() {
        return Optional.ofNullable(this.mainScreenColumnCount);
    }

    public Map<String, Integer> getSubScreenColumnCount() {
        return Collections.unmodifiableMap(this.subScreenColumnCount);
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

    /** {@code texture.noise} — pack path of the PNG that replaces the generated noisetex, if specified. */
    public Optional<String> getNoiseTexturePath() {
        return Optional.ofNullable(this.noiseTexturePath);
    }

    /** {@code texture.<stage>.<sampler>} overrides: stage → (sampler name → pack path / resource location). */
    public Map<TextureStage, Map<String, String>> getCustomTextures() {
        return Collections.unmodifiableMap(this.customTextures);
    }

    /** {@code customTexture.<name>} definitions: sampler name → pack path / resource location (all stages). */
    public Map<String, String> getIrisCustomTextures() {
        return Collections.unmodifiableMap(this.irisCustomTextures);
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
