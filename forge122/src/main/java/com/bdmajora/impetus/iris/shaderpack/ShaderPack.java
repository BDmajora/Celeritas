package com.bdmajora.impetus.iris.shaderpack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.iris.gl.shader.ShaderMacros;
import com.bdmajora.impetus.iris.shaderpack.include.AbsolutePackPath;
import com.bdmajora.impetus.iris.shaderpack.include.IncludeProcessor;
import com.bdmajora.impetus.iris.shaderpack.loading.ProgramArrayId;
import com.bdmajora.impetus.iris.shaderpack.loading.ProgramId;
import com.bdmajora.impetus.iris.shaderpack.materialmap.IdMap;
import com.bdmajora.impetus.iris.shaderpack.option.Profile;
import com.bdmajora.impetus.iris.shaderpack.option.ProfileSet;
import com.bdmajora.impetus.iris.shaderpack.option.ShaderPackOptions;
import com.bdmajora.impetus.iris.shaderpack.preprocessor.PropertiesPreprocessor;
import com.bdmajora.impetus.iris.shaderpack.texture.CustomTextureData;
import com.bdmajora.impetus.iris.shaderpack.texture.TextureFilteringData;
import com.bdmajora.impetus.iris.shaderpack.texture.TextureStage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A fully parsed shader pack: all of its GLSL files (keyed by {@link AbsolutePackPath} relative to the pack's
 * {@code shaders/} directory), the parsed {@code shaders.properties}, and the assembled {@link ProgramSet}.
 * <p>
 * Construction is Minecraft-free: it operates on an in-memory map of file contents so it can be unit-tested and so
 * pack reads (directory vs zip) are isolated in {@link ShaderPackLoader}. {@code #include} flattening happens here, up
 * front, so consumers receive ready-to-preprocess {@link ProgramSource}s.
 * <p>
 * Per-dimension program directories are supported in the common 1.12.2 form: a program declared under
 * {@code world0/} overrides the same program at the pack root. Other dimension folders are left for a later phase.
 */
public final class ShaderPack {
    /** The conventional pack-root path of the properties file, relative to {@code shaders/}. */
    public static final AbsolutePackPath PROPERTIES_PATH = AbsolutePackPath.fromAbsolutePath("/shaders.properties");
    /** Overworld override directory checked before the pack root. */
    private static final String OVERWORLD_DIR = "/world0";

    /** {@code !defined(IS_IRIS) && MC_VERSION < 11604} — see {@link #detectLegacyPrograms}. */
    private static final java.util.regex.Pattern LEGACY_BRANCH_PATTERN = java.util.regex.Pattern.compile(
            "!\\s*defined\\s*\\(?\\s*IS_IRIS\\s*\\)?\\s*&&\\s*MC_VERSION\\s*<\\s*(\\d+)");
    /** File extensions that make a pack file a shader stage rather than an include. */
    private static final Set<String> STAGE_EXTENSIONS =
            new java.util.HashSet<>(java.util.Arrays.asList("vsh", "fsh", "gsh", "csh", "tcs", "tes"));

    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    private final Map<AbsolutePackPath, String> sources;
    private final Map<AbsolutePackPath, byte[]> binaries;
    private final ShaderPackOptions shaderPackOptions;
    private final IncludeProcessor includeProcessor;
    private final ShaderProperties properties;
    private final ProgramSet baseProgramSet;
    /** The pack's ID maps (block/item/entity.properties), preprocessed with the active option values. */
    private final IdMap idMap;

    // --- Custom textures (Iris ShaderPack parity) ---
    /** {@code texture.noise} resolved to data, or {@code null} for the generated noisetex. */
    private final CustomTextureData customNoiseTexture;
    /** {@code texture.<stage>.<sampler>} directives resolved to data, keyed by stage then sampler name. */
    private final Map<TextureStage, Map<String, CustomTextureData>> customTextureDataMap =
            new EnumMap<>(TextureStage.class);
    /** {@code customTexture.<name>} directives resolved to data, keyed by sampler name. */
    private final Map<String, CustomTextureData> irisCustomTextureDataMap = new LinkedHashMap<>();

    public ShaderPack(Map<AbsolutePackPath, String> sources) {
        this(sources, Collections.emptyMap());
    }

    public ShaderPack(Map<AbsolutePackPath, String> sources, Map<String, String> changedConfigs) {
        this(sources, changedConfigs, Collections.emptyMap());
    }

    /**
     * @param sources        the raw source files of the pack (keyed relative to {@code shaders/}).
     * @param changedConfigs option values that differ from pack defaults, loaded from {@code <pack>.txt} and/or the
     *                       in-game menu. Applied to the sources before {@code #include} flattening.
     * @param binaries       the pack's binary assets ({@code .png} custom textures and their {@code .mcmeta}
     *                       sidecars), keyed relative to {@code shaders/} like {@code sources}.
     */
    public ShaderPack(Map<AbsolutePackPath, String> sources, Map<String, String> changedConfigs,
                      Map<AbsolutePackPath, byte[]> binaries) {
        this.sources = Collections.unmodifiableMap(new HashMap<>(sources));
        this.binaries = Collections.unmodifiableMap(new HashMap<>(binaries));

        // Discover options across every source file except the properties file, and apply the changed values. Must
        // run FIRST: the properties/ID-map preprocessing below needs the resolved option values as macros. The
        // include processor then flattens the EDITED sources so that option toggles/values are already baked in.
        Map<AbsolutePackPath, String> optionSources = new HashMap<>(this.sources);
        optionSources.remove(PROPERTIES_PATH);
        this.shaderPackOptions = new ShaderPackOptions(optionSources, changedConfigs);

        // The macro environment Iris feeds its PropertiesPreprocessor: MC_* environment defines plus the pack's
        // option values (enabled booleans as flag macros, string options as value macros).
        Map<String, String> propertiesDefines = getShaderDefines();

        // Iris parity: pipeline directives read from the PREPROCESSED contents (so #if MC_VERSION/option gates
        // resolve), menu-layout directives from the original. Option EDITS still never touch this file.
        String propertiesContents = this.sources.get(PROPERTIES_PATH);
        ShaderProperties parsedProperties = propertiesContents != null
                ? ShaderProperties.parse(propertiesContents,
                        PropertiesPreprocessor.preprocess(propertiesContents, propertiesDefines),
                        propertiesDefines,
                        Collections.emptySet())
                : ShaderProperties.empty();
        Set<String> profileDisabledPrograms = activeProfileDisabledPrograms(parsedProperties);
        this.properties = profileDisabledPrograms.isEmpty()
                ? parsedProperties
                : parsedProperties.withProfileDisabledPrograms(profileDisabledPrograms);

        // Feature-flag validation: a pack *requiring* a flag this port cannot honor must fail loudly and visibly
        // instead of rendering subtly wrong. Optional flags simply stay undefined for the pack to detect.
        java.util.List<String> unsupportedRequired = com.bdmajora.impetus.iris.features.FeatureFlags
                .findUnsupported(this.properties.getRaw().get("iris.features.required"));
        if (!unsupportedRequired.isEmpty()) {
            String missing = String.join(", ", unsupportedRequired);
            LOGGER.error("[Iris] This shader pack requires Iris features not supported by this port: {}", missing);
            com.bdmajora.impetus.engine.impl.notification.ImpetusNotifications.warn(
                    "Shader pack may not work correctly",
                    "Requires unsupported features:",
                    missing);
        }

        Map<AbsolutePackPath, String> flattenSources = new HashMap<>(this.sources);
        flattenSources.putAll(this.shaderPackOptions.getEditedSources());
        this.includeProcessor = new IncludeProcessor(flattenSources);

        this.baseProgramSet = buildProgramSet();

        this.idMap = new IdMap(this.sources, propertiesDefines);

        // Resolve the custom-texture directives to data, exactly like Iris's ShaderPack constructor: a texture that
        // fails to read is logged and dropped (the sampler then sees the normal render target / generated noise).
        this.customNoiseTexture = this.properties.getNoiseTexturePath().map(path -> {
            try {
                return readTexture(path);
            } catch (IOException e) {
                LOGGER.error("[Iris] Unable to read the custom noise texture at {}: {}", path, e.getMessage());
                return null;
            }
        }).orElse(null);

        this.properties.getCustomTextures().forEach((stage, texturePropertiesMap) -> {
            Map<String, CustomTextureData> innerCustomTextureDataMap = new LinkedHashMap<>();
            texturePropertiesMap.forEach((samplerName, path) -> {
                try {
                    innerCustomTextureDataMap.put(samplerName, readTexture(path));
                } catch (IOException e) {
                    LOGGER.error("[Iris] Unable to read the custom texture at {}: {}", path, e.getMessage());
                }
            });
            this.customTextureDataMap.put(stage, innerCustomTextureDataMap);
        });

        this.properties.getIrisCustomTextures().forEach((name, path) -> {
            try {
                this.irisCustomTextureDataMap.put(name, readTexture(path));
            } catch (IOException e) {
                LOGGER.error("[Iris] Unable to read the custom texture at {}: {}", path, e.getMessage());
            }
        });

        // Must run before anything compiles: both the terrain and the composite compile paths ask
        // ShaderMacros.forProgram which programs take their pre-Iris branch.
        ShaderMacros.setPackLegacyPrograms(detectLegacyPrograms(this.sources));
    }

    /**
     * The {@code !defined(IS_IRIS) && MC_VERSION < <newer than ours>} directive: the pack saying "here is the path I
     * authored for an OptiFine this old". Those programs get compiled without {@code IS_IRIS}/{@code IRIS_VERSION}
     * (see {@code ShaderMacros.setPackLegacyPrograms}) so they take that path instead of the Iris one, which on 1.12.2
     * is both what the pack intends and what actually works — Sildur's water does its reflection inline behind
     * {@code IS_IRIS} at {@code F0=0.5} over 85%-opaque water (opaque, mirror-like), while the 1.12.2 path defers it
     * to {@code composite1} at {@code F0=0.25} after the water has alpha-blended with the floor (see-through water).
     * <p>
     * The {@code &&} and the {@code <} are both load-bearing, and keep this from firing on packs that merely mention
     * {@code IS_IRIS}: BSL's {@code deferred1} has {@code MC_VERSION >= 10900 && !defined IS_IRIS} and
     * Complementary's {@code common.glsl} has {@code !defined IS_IRIS || MC_VERSION < 12109}; neither matches. Across
     * the nine packs on hand this selects exactly Sildur's {@code gbuffers_water} and {@code composite1} — the pair
     * that has to move together, since the 1.12.2 water branch writes the wave normal to {@code gl_FragData[2]}
     * ({@code DRAWBUFFERS:412}) and {@code composite1} reads it back from colortex2.
     * <p>
     * Scans the raw (un-flattened) sources on purpose: a match inside an {@code #include}d library says nothing about
     * which program should switch branches.
     */
    private static Set<String> detectLegacyPrograms(Map<AbsolutePackPath, String> rawSources) {
        Set<String> legacy = new java.util.HashSet<>();
        for (Map.Entry<AbsolutePackPath, String> entry : rawSources.entrySet()) {
            String name = programName(entry.getKey());
            if (name == null || legacy.contains(name)) {
                continue;
            }
            java.util.regex.Matcher matcher = LEGACY_BRANCH_PATTERN.matcher(entry.getValue());
            while (matcher.find()) {
                int version;
                try {
                    version = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException e) {
                    continue;
                }
                // Only a cutoff ABOVE ours means the legacy branch is the one 1.12.2 would take.
                if (version > ShaderMacros.MC_VERSION) {
                    legacy.add(name);
                    LOGGER.info("[Iris] {} has a pre-Iris path for MC < {}; compiling it without IS_IRIS", name, version);
                    break;
                }
            }
        }
        return legacy;
    }

    /** {@code /world1/composite1.fsh} → {@code composite1}; {@code null} for anything that is not a shader stage. */
    private static String programName(AbsolutePackPath path) {
        String file = path.getPathString();
        int slash = file.lastIndexOf('/');
        if (slash >= 0) {
            file = file.substring(slash + 1);
        }
        int dot = file.lastIndexOf('.');
        if (dot < 0 || !STAGE_EXTENSIONS.contains(file.substring(dot + 1))) {
            return null;
        }
        return file.substring(0, dot);
    }

    /**
     * Resolves one custom-texture directive value, mirroring Iris's {@code ShaderPack.readTexture}:
     * <ul>
     * <li>{@code namespace:path} → a resource-location texture looked up in the game's TextureManager at bind time
     * (with {@code minecraft:dynamic/lightmap_1} marking the live lightmap);</li>
     * <li>anything else → a PNG inside the pack (leading {@code /} tolerated, like Continuum 2.0.4), with
     * {@code blur}/{@code clamp} flags from the {@code <path>.mcmeta} sidecar (both default {@code false}).</li>
     * </ul>
     */
    private CustomTextureData readTexture(String path) throws IOException {
        String[] rawParts = path.trim().split("\\s+");
        if (rawParts.length > 1) {
            return readRawTexture(rawParts);
        }
        if (path.contains(":")) {
            String[] parts = path.split(":");
            if (parts.length > 2) {
                LOGGER.warn("[Iris] Resource location {} contained more than two parts?", path);
            }
            if (parts[0].equals("minecraft")
                    && (parts[1].equals("dynamic/lightmap_1") || parts[1].equals("dynamic/light_map_1"))) {
                return new CustomTextureData.LightmapMarker();
            }
            return new CustomTextureData.ResourceData(parts[0], parts[1]);
        }

        // NB: like Iris, this does not guarantee the path stays inside the pack; the leading-slash strip just fixes
        // packs that write "/lib/..." instead of "lib/...".
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        byte[] content = readBinary(path);

        boolean blur = false;
        boolean clamp = false;
        byte[] mcMeta = this.binaries.get(AbsolutePackPath.fromAbsolutePath("/" + path + ".mcmeta"));
        if (mcMeta != null) {
            try {
                JsonObject meta = new JsonParser()
                        .parse(new String(mcMeta, StandardCharsets.UTF_8)).getAsJsonObject();
                if (meta.get("texture") != null) {
                    JsonObject texture = meta.get("texture").getAsJsonObject();
                    if (texture.get("blur") != null) {
                        blur = texture.get("blur").getAsBoolean();
                    }
                    if (texture.get("clamp") != null) {
                        clamp = texture.get("clamp").getAsBoolean();
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.error("[Iris] Unable to read the custom texture mcmeta at {}.mcmeta, ignoring: {}",
                        path, e.getMessage());
            }
        }

        return new CustomTextureData.PngData(new TextureFilteringData(blur, clamp), content);
    }

    private CustomTextureData readRawTexture(String[] parts) throws IOException {
        String textureType = parts[1].toUpperCase(java.util.Locale.ROOT);
        if (textureType.equals("TEXTURE_3D")) {
            if (parts.length < 8) {
                throw new IOException("Malformed raw 3D texture definition");
            }
            return new CustomTextureData.RawData(textureType, parts[2],
                    parsePositiveInt(parts[3], "width"),
                    parsePositiveInt(parts[4], "height"),
                    parsePositiveInt(parts[5], "depth"),
                    parts[6], parts[7], readBinary(parts[0]));
        }
        if (textureType.equals("TEXTURE_2D")) {
            if (parts.length < 7) {
                throw new IOException("Malformed raw 2D texture definition");
            }
            return new CustomTextureData.RawData(textureType, parts[2],
                    parsePositiveInt(parts[3], "width"),
                    parsePositiveInt(parts[4], "height"),
                    1, parts[5], parts[6], readBinary(parts[0]));
        }
        throw new IOException("Unsupported raw texture target: " + parts[1]);
    }

    private byte[] readBinary(String path) throws IOException {
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        AbsolutePackPath texturePath = AbsolutePackPath.fromAbsolutePath("/" + path);
        byte[] content = this.binaries.get(texturePath);
        if (content == null) {
            throw new IOException("Texture file not found in pack: " + path);
        }
        return content;
    }

    private static int parsePositiveInt(String value, String field) throws IOException {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IOException("Raw texture " + field + " must be positive: " + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IOException("Bad raw texture " + field + ": " + value);
        }
    }

    /**
     * The macro set for preprocessing the pack's {@code *.properties} files: the GL-free {@code MC_*} environment
     * macros plus the pack's current option values, mirroring what Iris passes to its PropertiesPreprocessor.
     */
    public Map<String, String> getShaderDefines() {
        Map<String, String> defines = ShaderMacros.standard();
        this.shaderPackOptions.getOptionSet().getBooleanOptions().forEach((name, option) -> {
            if (this.shaderPackOptions.getOptionValues().getBooleanValueOrDefault(name)) {
                defines.put(name, "1");
            }
        });
        this.shaderPackOptions.getOptionSet().getStringOptions().forEach((name, option) ->
                defines.put(name, this.shaderPackOptions.getOptionValues().getStringValueOrDefault(name)));
        return defines;
    }

    /**
     * The macro set for GLSL source injection: environment macros ONLY ({@code MC_*}, {@code IRIS_*}). Option values
     * must never be injected into GLSL — they are already applied in place to the pack sources (OptiFine/Iris
     * semantics, {@link ShaderPackOptions}), so injecting them again redefines the pack's own {@code #define} lines
     * (driver error "macro redefined") and force-defines names packs use as stage/include guards or plain
     * identifiers (SuperDuperVanilla's {@code VERTEX}/{@code FRAGMENT} guards select which {@code main} to compile).
     */
    public Map<String, String> getEnvironmentDefines() {
        return ShaderMacros.standard();
    }

    private Set<String> activeProfileDisabledPrograms(ShaderProperties parsedProperties) {
        if (parsedProperties.getProfiles().isEmpty()) {
            return Collections.emptySet();
        }
        try {
            ProfileSet profileSet = ProfileSet.fromTree(
                    new LinkedHashMap<>(parsedProperties.getProfiles()),
                    this.shaderPackOptions.getOptionSet());
            ProfileSet.ProfileResult result = profileSet.scan(
                    this.shaderPackOptions.getOptionSet(),
                    this.shaderPackOptions.getOptionValues());
            if (!result.current.isPresent()) {
                return Collections.emptySet();
            }
            Profile current = result.current.get();
            return new LinkedHashSet<>(current.disabledPrograms);
        } catch (RuntimeException e) {
            LOGGER.warn("[Iris] Failed to parse shader pack profiles; profile program disables ignored", e);
            return Collections.emptySet();
        }
    }

    /** The pack's parsed block/item/entity ID maps. */
    public IdMap getIdMap() {
        return this.idMap;
    }

    /** {@code texture.noise} resolved to PNG data, or {@code null} when the pack keeps the generated noisetex. */
    public CustomTextureData getCustomNoiseTexture() {
        return this.customNoiseTexture;
    }

    /** {@code texture.<stage>.<sampler>} overrides resolved to data: stage → (sampler name → texture data). */
    public Map<TextureStage, Map<String, CustomTextureData>> getCustomTextureDataMap() {
        return Collections.unmodifiableMap(this.customTextureDataMap);
    }

    /** {@code customTexture.<name>} definitions resolved to data: sampler name → texture data (all stages). */
    public Map<String, CustomTextureData> getIrisCustomTextureDataMap() {
        return Collections.unmodifiableMap(this.irisCustomTextureDataMap);
    }

    public ShaderPackOptions getShaderPackOptions() {
        return this.shaderPackOptions;
    }

    public ShaderProperties getProperties() {
        return this.properties;
    }

    public ProgramSet getProgramSet() {
        return this.baseProgramSet;
    }

    public Map<AbsolutePackPath, String> getSources() {
        return this.sources;
    }

    private ProgramSet buildProgramSet() {
        ProgramSet set = new ProgramSet(this.properties);

        for (ProgramId id : ProgramId.values()) {
            ProgramSource source = readProgram(id.getSourceName());
            if (source != null) {
                set.put(id, source);
            }
        }

        for (ProgramArrayId arrayId : ProgramArrayId.values()) {
            ProgramSource[] arr = new ProgramSource[arrayId.getNumPrograms()];
            boolean any = false;
            for (int i = 0; i < arr.length; i++) {
                ProgramSource source = readProgram(arrayId.getSourceName(i));
                if (source != null) {
                    arr[i] = source;
                    any = true;
                }
            }
            if (any) {
                set.putArray(arrayId, arr);
            }
        }

        return set;
    }

    /**
     * Reads and flattens a program's stages by source name, or returns {@code null} if neither a vertex nor a fragment
     * stage exists for it anywhere in the pack.
     */
    private ProgramSource readProgram(String sourceName) {
        String vertex = readStage(sourceName, "vsh");
        String fragment = readStage(sourceName, "fsh");
        String compute = readStage(sourceName, "csh");
        if (vertex == null && fragment == null && compute == null) {
            return null;
        }
        String geometry = readStage(sourceName, "gsh");
        String tessControl = readStage(sourceName, "tcs");
        String tessEval = readStage(sourceName, "tes");
        return new ProgramSource(sourceName, vertex, geometry, tessControl, tessEval, fragment, compute);
    }

    private String readStage(String sourceName, String extension) {
        AbsolutePackPath path = locateStage(sourceName, extension);
        if (path == null) {
            return null;
        }
        return String.join("\n", this.includeProcessor.process(path));
    }

    /** Checks the overworld override directory first, then the pack root. */
    private AbsolutePackPath locateStage(String sourceName, String extension) {
        AbsolutePackPath overworld = AbsolutePackPath.fromAbsolutePath(
                OVERWORLD_DIR + "/" + sourceName + "." + extension);
        if (this.sources.containsKey(overworld)) {
            return overworld;
        }
        AbsolutePackPath root = AbsolutePackPath.fromAbsolutePath("/" + sourceName + "." + extension);
        return this.sources.containsKey(root) ? root : null;
    }
}
