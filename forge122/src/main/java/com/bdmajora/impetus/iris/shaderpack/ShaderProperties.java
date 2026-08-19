package com.bdmajora.impetus.iris.shaderpack;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.iris.shaderpack.preprocessor.PropertiesPreprocessor;
import com.bdmajora.impetus.iris.shaderpack.texture.TextureStage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * A parsed {@code shaders.properties} file (OptiFine format).
 * <p>
 * 1.12.2 packs use the classic OptiFine directives. Rather than enumerate every possible key, this parser keeps the
 * full key/value map and layers typed accessors over the handful of directives the pipeline actually consumes
 * (shadow configuration, cloud mode, per-program blend modes, per-program enable toggles, custom textures). Unknown
 * keys are preserved verbatim so later phases can read them without re-parsing.
 * <p>
 * Iris parity ({@code ShaderProperties} loads both a preprocessed and an original Properties object): the pipeline
 * directives are read from the <em>preprocessed</em> contents, so {@code #if MC_VERSION}/option-gated sections
 * resolve correctly — while the option-menu layout directives ({@code sliders}, {@code profile.*}, {@code screen*})
 * are read from the <em>original</em> contents, since the menu must show every option regardless of current values.
 * <p>
 * Parsing is deliberately a simple split-on-first-{@code =}: it avoids {@link java.util.Properties}' backslash-escape
 * surprises, which matter because GLSL-adjacent values occasionally contain {@code \}.
 */
public final class ShaderProperties {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");
    private static final List<String> LEGACY_RENDER_TARGETS =
            Arrays.asList("gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4");

    private final Map<String, String> raw;
    private final Map<String, String> original;
    private final Map<String, String> expressionDefines;
    private final Set<String> profileDisabledPrograms;

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
    /**
     * Raw {@code texture.<stage>.<sampler>} directives resolved to a minted {@code customtexN} sampler plus the
     * type-checked rename that redirects the stage's programs to it (Iris {@code customTexturePatching}).
     */
    private final List<com.bdmajora.impetus.iris.shaderpack.texture.CustomTexturePatch> customTexturePatches =
            new ArrayList<>();
    /** Counter behind the minted {@code customtexN} names; Iris's {@code customTexAmount}. */
    private int customTexAmount = 0;
    /** {@code size.buffer.colortexN = <w> <h>} — explicit render-target sizes, absolute or screen-relative. */
    private final Map<Integer, float[]> bufferSizes = new LinkedHashMap<>();
    /** Per axis, whether the {@link #bufferSizes} entry is a fraction of the render size rather than a texel count. */
    private final Map<Integer, boolean[]> bufferSizeRelative = new LinkedHashMap<>();
    /** {@code image.<name> = ...} — writable custom images (imageStore), declaration order. */
    private final List<com.bdmajora.impetus.iris.shaderpack.texture.CustomImageDefinition> irisCustomImages =
            new ArrayList<>();
    /** {@code flip.<program>.<target> = true|false}, including {@code deferred_pre}/{@code composite_pre}. */
    private final Map<String, Map<Integer, Boolean>> explicitFlips = new LinkedHashMap<>();

    /** {@code uniform.<type>.<name>} / {@code variable.<type>.<name>} custom uniform expressions. */
    private final com.bdmajora.impetus.iris.uniforms.custom.CustomUniforms.Builder customUniforms =
            new com.bdmajora.impetus.iris.uniforms.custom.CustomUniforms.Builder();

    private ShaderProperties(Map<String, String> preprocessed, Map<String, String> original,
                             Map<String, String> expressionDefines, Set<String> profileDisabledPrograms) {
        this.raw = Collections.unmodifiableMap(new LinkedHashMap<>(preprocessed));
        this.original = Collections.unmodifiableMap(new LinkedHashMap<>(original));
        this.expressionDefines = Collections.unmodifiableMap(new HashMap<>(expressionDefines));
        this.profileDisabledPrograms = Collections.unmodifiableSet(new java.util.LinkedHashSet<>(profileDisabledPrograms));
        parseMenuDirectives(original);
        parseCustomTextureDirectives();
        parseCustomUniformDirectives();
    }

    public static ShaderProperties empty() {
        return new ShaderProperties(Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptySet());
    }

    /** Parses without conditional evaluation — every {@code #if}-guarded line is read, last one wins. */
    public static ShaderProperties parse(String contents) {
        Map<String, String> map = parseMap(contents);
        return new ShaderProperties(map, map, Collections.emptyMap(), Collections.emptySet());
    }

    /**
     * @param original     the file exactly as shipped (menu-layout directives come from here, Iris parity).
     * @param preprocessed the file with conditionals resolved for the active defines/option values (everything the
     *                     pipeline consumes comes from here).
     */
    public static ShaderProperties parse(String original, String preprocessed) {
        return parse(original, preprocessed, Collections.emptyMap(), Collections.emptySet());
    }

    public static ShaderProperties parse(String original, String preprocessed,
                                         Map<String, String> expressionDefines,
                                         Set<String> profileDisabledPrograms) {
        return new ShaderProperties(parseMap(preprocessed), parseMap(original),
                expressionDefines, profileDisabledPrograms);
    }

    public ShaderProperties withProfileDisabledPrograms(Set<String> disabledPrograms) {
        return new ShaderProperties(this.raw, this.original, this.expressionDefines, disabledPrograms);
    }

    private static Map<String, String> parseMap(String contents) {
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
        return map;
    }

    /**
     * Parses {@code uniform.<type>.<name> = <expr>} and {@code variable.<type>.<name> = <expr>} directives into the
     * custom-uniforms builder. Mirrors Iris's ShaderProperties handling of the same keys.
     */
    private void parseCustomUniformDirectives() {
        this.raw.forEach((key, value) -> {
            boolean isUniform = key.startsWith("uniform.");
            boolean isVariable = key.startsWith("variable.");
            if (!isUniform && !isVariable) {
                return;
            }

            String remainder = key.substring((isUniform ? "uniform." : "variable.").length());
            String[] parts = remainder.split("\\.", 2);
            if (parts.length != 2) {
                LOGGER.warn("[Iris] Custom {} should take the form `{}.<type>.<name> = <expression>`; ignoring {}",
                        isUniform ? "uniforms" : "variables", isUniform ? "uniform" : "variable", key);
                return;
            }

            this.customUniforms.addVariable(parts[0], parts[1], value, isUniform);
        });
    }

    /** {@return the collected custom uniform/variable declarations} */
    public com.bdmajora.impetus.iris.uniforms.custom.CustomUniforms.Builder getCustomUniforms() {
        return this.customUniforms;
    }

    /** {@return the raw preprocessed key→value directives (read-only view)} */
    public Map<String, String> getRaw() {
        return Collections.unmodifiableMap(this.raw);
    }

    /**
     * Parses the option-menu layout directives ({@code sliders}, {@code profile.*}, {@code screen}, {@code screen.*},
     * {@code screen.columns}, {@code screen.*.columns}) from the ORIGINAL (non-preprocessed) contents — the menu must
     * present every option regardless of the currently active values. Mirrors Iris's ShaderProperties handling.
     */
    private void parseMenuDirectives(Map<String, String> original) {
        original.forEach((key, value) -> {
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
     * Multi-token values are Iris raw-texture definitions ({@code <path> <type> <format> ...}) and are resolved when
     * the shader pack loads its texture data.
     */
    private void parseCustomTextureDirectives() {
        this.raw.forEach((key, value) -> {
            if (key.equals("texture.noise")) {
                this.noiseTexturePath = value;
            } else if (key.startsWith("flip.")) {
                parseExplicitFlip(key, value);
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
                String[] parts = value.trim().split("\\s+");
                if (parts.length > 1) {
                    // A raw texture definition (<path> <target> <format> ... ). Iris does NOT hijack the sampler's
                    // unit for the whole stage here: it mints a new sampler name and renames the identifier only in
                    // the stage's programs that declare it with a matching sampler type. Photon depends on this —
                    // its colortex6 is a sampler3D noise lookup in deferred/deferred1 but a plain sampler2D buffer
                    // in deferred3/deferred4, and feeding the 3D texture to the latter makes them read black.
                    String textureType = rawTextureType(parts);
                    if (textureType == null) {
                        LOGGER.warn("[Iris] Unknown raw texture directive for {}: {}", key, value);
                        return;
                    }
                    String newSamplerName = "customtex" + this.customTexAmount++;
                    this.irisCustomTextures.put(newSamplerName, value.trim());
                    this.customTexturePatches.add(
                            new com.bdmajora.impetus.iris.shaderpack.texture.CustomTexturePatch(
                                    samplerName, stage.get(), textureType, newSamplerName));
                    return;
                }
                this.customTextures
                        .computeIfAbsent(stage.get(), s -> new LinkedHashMap<>())
                        .put(samplerName, value);
            } else if (key.startsWith("size.buffer.")) {
                parseBufferSize(key, value);
            } else if (key.startsWith("image.")) {
                String name = key.substring("image.".length());
                com.bdmajora.impetus.iris.shaderpack.texture.CustomImageDefinition definition =
                        com.bdmajora.impetus.iris.shaderpack.texture.CustomImageDefinition.parse(name, value);
                if (definition != null) {
                    this.irisCustomImages.add(definition);
                }
            } else if (key.startsWith("customTexture.")) {
                String name = key.substring("customTexture.".length());
                if (name.isEmpty()) {
                    LOGGER.warn("[Iris] Malformed custom texture directive, ignoring: {}", key);
                    return;
                }
                this.irisCustomTextures.put(name, value.trim());
            }
        });
    }

    /**
     * The texture target of a raw {@code texture.*} definition, from its token count — the same rule as Iris's
     * {@code ShaderProperties}: 6 tokens is 1D ({@code <path> <type> <format> <w> <pixelFormat> <pixelType>}), 7 is
     * whatever {@code <type>} says (2D or rectangle), 8 is 3D. Anything else is malformed.
     */
    private static String rawTextureType(String[] parts) {
        switch (parts.length) {
            case 6:
                return "TEXTURE_1D";
            case 7:
                return parts[1].toUpperCase(Locale.ROOT);
            case 8:
                return "TEXTURE_3D";
            default:
                return null;
        }
    }

    /**
     * {@code size.buffer.colortexN = <width> <height>}. Iris treats a pair that parses as integers as an absolute
     * texel count and anything else as a fraction of the render size, so {@code 512 512} is a fixed 512x512 buffer
     * while {@code 0.5 0.5} is half-resolution.
     */
    private void parseBufferSize(String key, String value) {
        String targetName = key.substring("size.buffer.".length()).trim();
        Integer index = colorTargetIndex(targetName);
        if (index == null) {
            LOGGER.warn("[Iris] Unknown render target '{}' in {}, ignoring it", targetName, key);
            return;
        }
        String[] parts = value.trim().split("\\s+");
        if (parts.length != 2) {
            LOGGER.warn("[Iris] {} needs exactly two values (got '{}'), ignoring it", key, value);
            return;
        }
        // Complementary writes `size.buffer.colortex1 = REFLECTION_RES REFLECTION_RES`, so a token that is not a
        // number is resolved against the pack's option values before parsing.
        parts[0] = resolveNumericMacro(parts[0]);
        parts[1] = resolveNumericMacro(parts[1]);
        try {
            // Iris (TextureScaleOverride) decides this PER AXIS: a token containing '.' is a fraction of the render
            // size, one without is a texel count. So `512 0.5` is a fixed 512 wide by half-height, and checking the
            // text rather than the parsed value matters — "1 1" is a 1x1 buffer, "1.0 1.0" is full resolution.
            boolean xRelative = parts[0].contains(".");
            boolean yRelative = parts[1].contains(".");
            float x = Float.parseFloat(parts[0]);
            float y = Float.parseFloat(parts[1]);
            if (x <= 0.0f || y <= 0.0f) {
                LOGGER.warn("[Iris] {} has a non-positive size ({} {}), ignoring it", key, parts[0], parts[1]);
                return;
            }
            this.bufferSizes.put(index, new float[]{x, y});
            this.bufferSizeRelative.put(index, new boolean[]{xRelative, yRelative});
        } catch (NumberFormatException e) {
            LOGGER.warn("[Iris] Malformed size in {} ('{}'), ignoring it", key, value);
        }
    }

    /**
     * Resolves an option macro used where a number is expected. Returns the token unchanged when it already looks
     * numeric or when the pack defines no such option, in which case the caller's parse fails and warns.
     */
    private String resolveNumericMacro(String token) {
        if (token.isEmpty() || Character.isDigit(token.charAt(0)) || token.charAt(0) == '.'
                || token.charAt(0) == '-') {
            return token;
        }
        String resolved = this.expressionDefines.get(token);
        return resolved == null ? token : resolved.trim();
    }

    /**
     * Explicit render-target sizes. The value is {@code {width, height}}, in texels when
     * {@link #isBufferSizeRelative} is false and as a fraction of the render size when it is true.
     */
    public Map<Integer, float[]> getBufferSizes() {
        return Collections.unmodifiableMap(this.bufferSizes);
    }

    /** @return {@code {xRelative, yRelative}} for the target, or {@code {false, false}} when it declares no size. */
    public boolean[] getBufferSizeRelative(int index) {
        boolean[] flags = this.bufferSizeRelative.get(index);
        return flags == null ? new boolean[]{false, false} : flags.clone();
    }

    private void parseExplicitFlip(String key, String value) {
        String rest = key.substring("flip.".length());
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot == rest.length() - 1) {
            LOGGER.warn("[Iris] Malformed explicit flip directive, ignoring: {}", key);
            return;
        }
        Optional<Boolean> shouldFlip = parseBooleanValue(value);
        if (!shouldFlip.isPresent()) {
            LOGGER.warn("[Iris] Invalid explicit flip value, ignoring: {} = {}", key, value);
            return;
        }
        Integer target = colorTargetIndex(rest.substring(dot + 1));
        if (target == null) {
            LOGGER.warn("[Iris] Unknown explicit flip target, ignoring: {}", key);
            return;
        }
        this.explicitFlips
                .computeIfAbsent(rest.substring(0, dot), ignored -> new LinkedHashMap<>())
                .put(target, shouldFlip.get());
    }

    private static Integer colorTargetIndex(String name) {
        if (name.startsWith("colortex")) {
            try {
                return Integer.parseInt(name.substring("colortex".length()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        int legacyIndex = LEGACY_RENDER_TARGETS.indexOf(name);
        return legacyIndex >= 0 ? legacyIndex : null;
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

    // ------------------------------------------------------------------ vanilla feature toggles
    // OptiFine's shaders.properties lets a pack suppress vanilla world features it draws itself. Every one of these
    // is a plain true/false key; absent means "leave vanilla alone".

    /** {@code sun} — draw the vanilla sun quad. Packs that render their own celestial bodies set this false. */
    public Optional<Boolean> getRenderSun() {
        return getBoolean("sun");
    }

    /** {@code moon} — draw the vanilla moon quad. */
    public Optional<Boolean> getRenderMoon() {
        return getBoolean("moon");
    }

    /** {@code stars} — draw the vanilla star field (Iris extension; OptiFine folds stars into the sky). */
    public Optional<Boolean> getRenderStars() {
        return getBoolean("stars");
    }

    /** {@code sky} — draw the vanilla sky dome/void plane at all (Iris extension). */
    public Optional<Boolean> getRenderSky() {
        return getBoolean("sky");
    }

    /** {@code vignette} — draw vanilla's screen vignette overlay. */
    public Optional<Boolean> getRenderVignette() {
        return getBoolean("vignette");
    }

    /** {@code underwaterOverlay} — draw vanilla's underwater texture overlay. */
    public Optional<Boolean> getRenderUnderwaterOverlay() {
        return getBoolean("underwaterOverlay");
    }

    /** {@code weather} — draw vanilla rain/snow particles (Iris extension). */
    public Optional<Boolean> getRenderWeather() {
        return getBoolean("weather");
    }

    /** {@code beacon.beam.depth} — whether the beacon beam writes depth. */
    public Optional<Boolean> getBeaconBeamDepth() {
        return getBoolean("beacon.beam.depth");
    }

    /** {@code rain.depth} — whether rain/snow writes depth. */
    public Optional<Boolean> getRainDepth() {
        return getBoolean("rain.depth");
    }

    /** {@code separateAo} — feed vanilla's ambient occlusion as vertex colour separately from block light. */
    public Optional<Boolean> getSeparateAo() {
        return getBoolean("separateAo");
    }

    /** {@code oldLighting} — keep vanilla's fixed-function directional block shading. */
    public Optional<Boolean> getOldLighting() {
        return getBoolean("oldLighting");
    }

    /** {@code oldHandLight} — use the legacy held-light behaviour instead of {@code heldBlockLightValue}. */
    public Optional<Boolean> getOldHandLight() {
        return getBoolean("oldHandLight");
    }

    /** {@code dynamicHandLight} — let the held item emit light through the {@code heldBlockLightValue} uniform. */
    public Optional<Boolean> getDynamicHandLight() {
        return getBoolean("dynamicHandLight");
    }

    /** {@code frustum.culling} — allow vanilla frustum culling in the main pass. */
    public Optional<Boolean> getFrustumCulling() {
        return getBoolean("frustum.culling");
    }

    /** {@code occlusion.culling} — allow vanilla occlusion culling in the main pass. */
    public Optional<Boolean> getOcclusionCulling() {
        return getBoolean("occlusion.culling");
    }

    /** {@code backFace.solid|cutout|cutoutMipped|translucent} — per-terrain-layer back-face culling override. */
    public Optional<Boolean> getBackFaceCulling(String layer) {
        return getBoolean("backFace." + layer);
    }

    // ------------------------------------------------------------------ shadow pass content

    /** {@code shadowTerrain} — draw terrain into the shadow map. */
    public Optional<Boolean> getShadowTerrain() {
        return getBoolean("shadowTerrain");
    }

    /** {@code shadowTranslucent} — draw translucent terrain into the shadow map (colored/water shadows). */
    public Optional<Boolean> getShadowTranslucent() {
        return getBoolean("shadowTranslucent");
    }

    /** {@code shadowEntities} — draw entities into the shadow map. */
    public Optional<Boolean> getShadowEntities() {
        return getBoolean("shadowEntities");
    }

    /** {@code shadowBlockEntities} — draw block entities (TESRs) into the shadow map. */
    public Optional<Boolean> getShadowBlockEntities() {
        return getBoolean("shadowBlockEntities");
    }

    /** {@code shadowLightBlockEntities} — draw only light-emitting block entities into the shadow map. */
    public Optional<Boolean> getShadowLightBlockEntities() {
        return getBoolean("shadowLightBlockEntities");
    }

    /** {@code shadowPlayer} — draw the player model into the shadow map. */
    public Optional<Boolean> getShadowPlayer() {
        return getBoolean("shadowPlayer");
    }

    /**
     * {@code shadow.culling} = {@code on} | {@code off} | {@code reversed}. Both Photon and Complementary ask for
     * {@code reversed}, which keeps geometry between the light and the camera that a normal frustum test would drop.
     */
    public Optional<String> getShadowCulling() {
        return get("shadow.culling").map(s -> s.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ pipeline behaviour flags

    /** {@code voxelizeLightBlocks} — submit light-emitting blocks to the shadow pass for voxelization. */
    public Optional<Boolean> getVoxelizeLightBlocks() {
        return getBoolean("voxelizeLightBlocks");
    }

    /** {@code separateEntityDraws} — draw entities in their own pass, after the deferred chain. */
    public Optional<Boolean> getSeparateEntityDraws() {
        return getBoolean("separateEntityDraws");
    }

    /**
     * {@code particles.ordering} = {@code mixed} | {@code after} | {@code before}, falling back to OptiFine's older
     * {@code particles.before.deferred} boolean.
     * <p>
     * Iris honours both, with the newer directive winning when present ({@code ShaderProperties} only applies
     * {@code particles.before.deferred} while the setting is still {@code UNSET}). The legacy spelling is not a dead
     * letter here: MakeUp-UltraFast and E-LITE both declare {@code particles.before.deferred = true} and nothing else,
     * so ignoring it left their particles drawing after the deferred chain instead of before it.
     */
    public Optional<String> getParticleOrdering() {
        Optional<String> ordering = get("particles.ordering").map(s -> s.toLowerCase(Locale.ROOT));
        if (ordering.isPresent()) {
            return ordering;
        }
        return getBoolean("particles.before.deferred").orElse(Boolean.FALSE)
                ? Optional.of("before")
                : Optional.empty();
    }

    /** {@code prepareBeforeShadow} — run the prepare family before the shadow map instead of after. */
    public Optional<Boolean> getPrepareBeforeShadow() {
        return getBoolean("prepareBeforeShadow");
    }

    /** {@code allowConcurrentCompute} — skip the memory barrier between consecutive compute dispatches. */
    public Optional<Boolean> getAllowConcurrentCompute() {
        return getBoolean("allowConcurrentCompute");
    }

    /** {@code supportsColorCorrection} — the pack handles the output colour space itself. */
    public Optional<Boolean> getSupportsColorCorrection() {
        return getBoolean("supportsColorCorrection");
    }

    /**
     * {@code breaksAnisotropy} — the pack is incompatible with anisotropic filtering on the block atlas. Parsed for
     * parity only, and unused: this renderer never anisotropically filters the atlas (see BlockAtlasFiltering).
     */
    public Optional<Boolean> getBreaksAnisotropy() {
        return getBoolean("breaksAnisotropy");
    }

    /** {@code skipAllRendering} — draw nothing but the composite chain (debug/benchmark packs). */
    public Optional<Boolean> getSkipAllRendering() {
        return getBoolean("skipAllRendering");
    }

    /**
     * {@code fallbackTex} — the texture index a pack nominates as the stand-in for a sampler it did not bind.
     * Parsed and exposed for parity; Iris likewise parses it and has no consumer, so nothing reads it here either.
     */
    public OptionalInt getFallbackTex() {
        return getInt("fallbackTex");
    }

    /**
     * Per-program alpha test override, e.g. {@code alphaTest.gbuffers_water = GREATER 0.0001} or
     * {@code alphaTest.gbuffers_terrain = off}.
     */
    public Optional<String> getAlphaTestOverride(String programName) {
        return get("alphaTest." + programName);
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

    /** Raw {@code texture.<stage>.<sampler>} directives as type-checked sampler renames (see the patch's javadoc). */
    public List<com.bdmajora.impetus.iris.shaderpack.texture.CustomTexturePatch> getCustomTexturePatches() {
        return Collections.unmodifiableList(this.customTexturePatches);
    }

    /** {@code image.<name>} definitions in declaration order (Iris custom writable images). */
    public List<com.bdmajora.impetus.iris.shaderpack.texture.CustomImageDefinition> getIrisCustomImages() {
        return Collections.unmodifiableList(this.irisCustomImages);
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
        if (isProfileDisabled(programName)) {
            return Optional.of(Boolean.FALSE);
        }

        return firstBoolean(
                "program.world0/" + programName + ".enabled",
                "program.world0/" + programName + "..enabled",
                "program." + programName + ".enabled",
                "program." + programName + "..enabled");
    }

    private boolean isProfileDisabled(String programName) {
        return this.profileDisabledPrograms.contains(programName)
                || this.profileDisabledPrograms.contains("world0/" + programName);
    }

    private Optional<Boolean> firstBoolean(String... keys) {
        for (String key : keys) {
            Optional<Boolean> value = getBoolean(key);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    public Map<Integer, Boolean> getExplicitFlips(String programName) {
        Map<Integer, Boolean> flips = this.explicitFlips.get(programName);
        return flips == null ? Collections.emptyMap() : Collections.unmodifiableMap(flips);
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
        Optional<Boolean> literal = parseBooleanValue(value);
        if (literal.isPresent()) {
            return literal;
        }
        return PropertiesPreprocessor.evaluateBooleanExpression(value, this.expressionDefines);
    }

    private static Optional<Boolean> parseBooleanValue(String value) {
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
