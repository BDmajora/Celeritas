package com.bdmajora.impetus.umbra.shaderpack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.umbra.gl.shader.ShaderMacros;
import com.bdmajora.impetus.umbra.shaderpack.include.AbsolutePackPath;
import com.bdmajora.impetus.umbra.shaderpack.include.IncludeProcessor;
import com.bdmajora.impetus.umbra.shaderpack.loading.ProgramArrayId;
import com.bdmajora.impetus.umbra.shaderpack.loading.ProgramId;
import com.bdmajora.impetus.umbra.shaderpack.materialmap.IdMap;
import com.bdmajora.impetus.umbra.shaderpack.option.Profile;
import com.bdmajora.impetus.umbra.shaderpack.option.ProfileSet;
import com.bdmajora.impetus.umbra.shaderpack.option.ShaderPackOptions;
import com.bdmajora.impetus.umbra.shaderpack.preprocessor.PropertiesPreprocessor;
import com.bdmajora.impetus.umbra.shaderpack.texture.CustomTextureData;
import com.bdmajora.impetus.umbra.shaderpack.texture.TextureFilteringData;
import com.bdmajora.impetus.umbra.shaderpack.texture.TextureStage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// A fully parsed pack: every GLSL file by path, shaders.properties, and the assembled ProgramSet
// Works over an in-memory map so it is free of Minecraft and testable; #include flattening happens here
// world0/ overrides the root; other dimension folders are not handled
public final class ShaderPack {
    // The conventional location of shaders.properties, relative to shaders/
    public static final AbsolutePackPath PROPERTIES_PATH = AbsolutePackPath.fromAbsolutePath("/shaders.properties");
    // The overworld override directory, checked BEFORE the pack root so a world0/ program wins
    private static final String OVERWORLD_DIR = "/world0";

    // Matches `!defined(IS_IRIS) && MC_VERSION < <n>` — the shape detectLegacyPrograms below looks for
    private static final java.util.regex.Pattern LEGACY_BRANCH_PATTERN = java.util.regex.Pattern.compile(
            "!\\s*defined\\s*\\(?\\s*IS_IRIS\\s*\\)?\\s*&&\\s*MC_VERSION\\s*<\\s*(\\d+)");
    // The extensions that make a pack file a shader STAGE rather than an include — everything else in the pack is
    // library source that only reaches the driver by being included
    private static final Set<String> STAGE_EXTENSIONS =
            new java.util.HashSet<>(java.util.Arrays.asList("vsh", "fsh", "gsh", "csh", "tcs", "tes"));

    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");

    private final Map<AbsolutePackPath, String> sources;
    private final Map<AbsolutePackPath, byte[]> binaries;
    private final ShaderPackOptions shaderPackOptions;
    private final IncludeProcessor includeProcessor;
    private final ShaderProperties properties;
    private final ProgramSet baseProgramSet;
    // The features this pack DECLARED in iris.features.required/optional and that this port can honour
    // Distinct from the IRIS_FEATURE_<NAME> GLSL defines, which advertise everything the port supports so a pack
    // can #ifdef on availability
    // Iris draws exactly the same distinction — its hasFeature reads the declared set, while the defines come from
    // isUsable() — and conflating the two would tell a pack it opted into something it never asked for
    private final Set<com.bdmajora.impetus.umbra.features.FeatureFlags> activeFeatures;
    // The pack's block/item/entity.properties maps, preprocessed with the ACTIVE option values — a pack gates its
    // ID map on its own options, so parsing them with the defaults would give the wrong ids
    private final IdMap idMap;

    // --- Custom textures (Umbra ShaderPack parity) ---
    // texture.noise resolved to data, or null meaning "keep the generated noisetex"
    private final CustomTextureData customNoiseTexture;
    // texture.<stage>.<sampler> directives resolved to data, keyed by stage and then by sampler name — stage first
    // because the same sampler name legitimately means different things in different stages
    private final Map<TextureStage, Map<String, CustomTextureData>> customTextureDataMap =
            new EnumMap<>(TextureStage.class);
    // customTexture.<name> directives resolved to data, keyed by sampler name — these are stage-independent, which
    // is why they need no stage dimension
    private final Map<String, CustomTextureData> irisCustomTextureDataMap = new LinkedHashMap<>();

    public ShaderPack(Map<AbsolutePackPath, String> sources) {
        this(sources, Collections.emptyMap());
    }

    public ShaderPack(Map<AbsolutePackPath, String> sources, Map<String, String> changedConfigs) {
        this(sources, changedConfigs, Collections.emptyMap());
    }

    // sources is the pack's raw text files, keyed relative to shaders/
    // changedConfigs is only the option values that DIFFER from the pack's defaults, loaded from <pack>.txt and the
    // in-game menu — applied to the sources BEFORE include flattening, since an option can gate an #include
    // binaries is the pack's binary assets, .png custom textures and their .mcmeta sidecars, keyed the same way
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

        // The macro environment Umbra feeds its PropertiesPreprocessor: MC_* environment defines plus the pack's
        // option values (enabled booleans as flag macros, string options as value macros).
        Map<String, String> propertiesDefines = getShaderDefines();

        // Umbra parity: pipeline directives read from the PREPROCESSED contents (so #if MC_VERSION/option gates
        // resolve), menu-layout directives from the original. Option EDITS still never touch this file.
        String propertiesContents = this.sources.get(PROPERTIES_PATH);
        ShaderProperties parsedProperties = propertiesContents != null
                ? ShaderProperties.parse(propertiesContents,
                        PropertiesPreprocessor.preprocessProperties(propertiesContents, propertiesDefines),
                        propertiesDefines,
                        Collections.emptySet())
                : ShaderProperties.empty();
        Set<String> profileDisabledPrograms = activeProfileDisabledPrograms(parsedProperties);
        this.properties = profileDisabledPrograms.isEmpty()
                ? parsedProperties
                : parsedProperties.withProfileDisabledPrograms(profileDisabledPrograms);

        // Feature-flag validation: a pack *requiring* a flag this port cannot honor must fail loudly and visibly
        // instead of rendering subtly wrong. Optional flags simply stay undefined for the pack to detect.
        this.activeFeatures = com.bdmajora.impetus.umbra.features.FeatureFlags.parseDeclared(
                this.properties.getRaw().get("iris.features.required"),
                this.properties.getRaw().get("iris.features.optional"));
        java.util.List<String> unsupportedRequired = com.bdmajora.impetus.umbra.features.FeatureFlags
                .findUnsupported(this.properties.getRaw().get("iris.features.required"));
        if (!unsupportedRequired.isEmpty()) {
            String missing = String.join(", ", unsupportedRequired);
            LOGGER.error("[Umbra] This shader pack requires Umbra features not supported by this port: {}", missing);
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

        // Resolve the custom-texture directives to data, exactly like Umbra's ShaderPack constructor: a texture that
        // fails to read is logged and dropped (the sampler then sees the normal render target / generated noise).
        this.customNoiseTexture = this.properties.getNoiseTexturePath().map(path -> {
            try {
                return readTexture(path);
            } catch (IOException e) {
                LOGGER.error("[Umbra] Unable to read the custom noise texture at {}: {}", path, e.getMessage());
                return null;
            }
        }).orElse(null);

        this.properties.getCustomTextures().forEach((stage, texturePropertiesMap) -> {
            Map<String, CustomTextureData> innerCustomTextureDataMap = new LinkedHashMap<>();
            texturePropertiesMap.forEach((samplerName, path) -> {
                try {
                    innerCustomTextureDataMap.put(samplerName, readTexture(path));
                } catch (IOException e) {
                    LOGGER.error("[Umbra] Unable to read the custom texture at {}: {}", path, e.getMessage());
                }
            });
            this.customTextureDataMap.put(stage, innerCustomTextureDataMap);
        });

        this.properties.getUmbraCustomTextures().forEach((name, path) -> {
            try {
                this.irisCustomTextureDataMap.put(name, readTexture(path));
            } catch (IOException e) {
                LOGGER.error("[Umbra] Unable to read the custom texture at {}: {}", path, e.getMessage());
            }
        });

        // Must run before anything compiles: both the terrain and the composite compile paths ask
        // ShaderMacros.forProgram which programs take their pre-Umbra branch.
        ShaderMacros.setPackLegacyPrograms(detectLegacyPrograms(this.sources));
        // Likewise for the raw-custom-texture renames: the gbuffers/terrain/shadow compile paths reach the transform
        // from static contexts with no pack handle.
        com.bdmajora.impetus.umbra.shaderpack.texture.CustomTextureTransformer.setActivePatches(
                this.properties.getCustomTexturePatches());
    }

    // Finds the programs whose source contains `!defined(IS_IRIS) && MC_VERSION < <newer than ours>` — the pack
    // saying "here is the path I authored for an OptiFine this old"
    // Those programs are then compiled WITHOUT IS_IRIS and IRIS_VERSION, so they take that authored path instead of
    // the Iris one. On 1.12.2 that is both what the pack intends and what actually works: Sildur's water does its
    // reflection inline behind IS_IRIS at F0=0.5 over 85%-opaque water, giving opaque mirror-like water, while its
    // 1.12.2 path defers the reflection to composite1 at F0=0.25 after the water has alpha-blended with the floor,
    // giving see-through water
    //
    // The && and the < are both load-bearing, and are what keep this from firing on packs that merely MENTION
    // IS_IRIS: BSL's deferred1 has `MC_VERSION >= 10900 && !defined IS_IRIS` and Complementary's common.glsl has
    // `!defined IS_IRIS || MC_VERSION < 12109`, and neither matches
    // Across the nine packs on hand this selects exactly Sildur's gbuffers_water and composite1 — which is the pair
    // that HAS to move together, since the 1.12.2 water branch writes the wave normal to gl_FragData[2] under
    // DRAWBUFFERS:412 and composite1 reads it back from colortex2
    //
    // Scans the RAW un-flattened sources on purpose: a match inside an included library says nothing about which
    // program should switch branches
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
                    break;
                }
            }
        }
        return legacy;
    }

    // Reduces a path to its program name — /world1/composite1.fsh gives composite1 — and returns null for anything
    // that is not a shader stage at all
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

    // Resolves one custom-texture directive value, mirroring Iris's ShaderPack.readTexture
    // A namespace:path value becomes a resource-location texture, looked up through the game's TextureManager at
    // bind time rather than now — with minecraft:dynamic/lightmap_1 marking the live lightmap specially
    // Anything else is a PNG inside the pack, with a leading / tolerated because Continuum 2.0.4 writes one, and
    // blur/clamp flags read from the <path>.mcmeta sidecar, both defaulting to false
    private CustomTextureData readTexture(String path) throws IOException {
        String[] rawParts = path.trim().split("\\s+");
        if (rawParts.length > 1) {
            return readRawTexture(rawParts);
        }
        if (path.contains(":")) {
            String[] parts = path.split(":");
            if (parts.length > 2) {
                LOGGER.warn("[Umbra] Resource location {} contained more than two parts?", path);
            }
            if (parts[0].equals("minecraft")
                    && (parts[1].equals("dynamic/lightmap_1") || parts[1].equals("dynamic/light_map_1"))) {
                return new CustomTextureData.LightmapMarker();
            }
            return new CustomTextureData.ResourceData(parts[0], parts[1]);
        }

        // NB: like Umbra, this does not guarantee the path stays inside the pack; the leading-slash strip just fixes
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
                LOGGER.error("[Umbra] Unable to read the custom texture mcmeta at {}.mcmeta, ignoring: {}",
                        path, e.getMessage());
            }
        }

        return new CustomTextureData.PngData(new TextureFilteringData(blur, clamp), content);
    }

    // texture.<stage>.<name> = <path> <type> <format> <w> <h> [<d>] <pixelFormat> <pixelType>
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

    // A binary resource from the pack, by pack-relative path
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

    // Fails with the field named, since a raw texture directive has many
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

    // The macro set for preprocessing the pack's *.properties files: the GL-free MC_* environment macros PLUS the
    // pack's current option values, matching what Iris hands its own PropertiesPreprocessor
    // Option values belong here and deliberately not in getEnvironmentDefines below — see that method
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

    // The macro set for GLSL source injection: environment macros ONLY, the MC_* and IRIS_* set
    // Option values must never be injected into GLSL. They are already applied in place to the pack sources, which
    // is the OptiFine and Iris semantics, so injecting them again redefines the pack's own #define lines and the
    // driver rejects it with "macro redefined"
    // Worse, it force-defines names packs use as stage or include guards: SuperDuperVanilla's VERTEX and FRAGMENT
    // guards select which main() gets compiled, so defining both compiles neither correctly
    public Map<String, String> getEnvironmentDefines() {
        Map<String, String> macros = ShaderMacros.standard();

        // OptiFine's ShaderMacros: two shaders.properties switches are exposed to GLSL as macros so a pack can adapt
        // its own lighting to the setting it asked for. Both default to true when the pack says nothing, matching
        // Shaders.isOldHandLight()/isOldLighting().
        if (this.properties.getOldHandLight().orElse(Boolean.TRUE)) {
            macros.put("MC_OLD_HAND_LIGHT", "");
        }
        if (this.properties.getOldLighting().orElse(Boolean.TRUE)) {
            macros.put("MC_OLD_LIGHTING", "");
        }
        // `supportsColorCorrection` means the pack converts to the output colour space itself, so Umbra hands it the
        // COLOR_SPACE_* enumeration to compare `currentColorSpace` against.
        if (this.properties.getSupportsColorCorrection().orElse(Boolean.FALSE)) {
            for (com.bdmajora.impetus.umbra.pipeline.ColorSpaceConverter.ColorSpace space
                    : com.bdmajora.impetus.umbra.pipeline.ColorSpaceConverter.ColorSpace.values()) {
                macros.put("COLOR_SPACE_" + space.name(), Integer.toString(space.ordinal()));
            }
        }
        return macros;
    }

    // Programs the currently selected profile turns off
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
            LOGGER.warn("[Umbra] Failed to parse shader pack profiles; profile program disables ignored", e);
            return Collections.emptySet();
        }
    }

    // The pack's parsed block, item and entity ID maps
    public IdMap getIdMap() {
        return this.idMap;
    }

    // texture.noise as resolved data, or null when the pack keeps the generated noisetex
    public CustomTextureData getCustomNoiseTexture() {
        return this.customNoiseTexture;
    }

    // The stage-scoped custom-texture overrides: stage, then sampler name, then the resolved data
    public Map<TextureStage, Map<String, CustomTextureData>> getCustomTextureDataMap() {
        return Collections.unmodifiableMap(this.customTextureDataMap);
    }

    // The stage-independent customTexture.<name> definitions: sampler name to resolved data, live in every stage
    public Map<String, CustomTextureData> getUmbraCustomTextureDataMap() {
        return Collections.unmodifiableMap(this.irisCustomTextureDataMap);
    }

    // The option set and current values
    public ShaderPackOptions getShaderPackOptions() {
        return this.shaderPackOptions;
    }

    // Parsed shaders.properties
    public ShaderProperties getProperties() {
        return this.properties;
    }

    // Every program source, by id
    public ProgramSet getProgramSet() {
        return this.baseProgramSet;
    }

    // Whether the pack ASKED for a feature — which is a different question from whether this port can provide it
    // Iris gates real pipeline behaviour on exactly this, and the distinction matters concretely: a pack that never
    // opted into SEPARATE_HARDWARE_SAMPLERS expects shadowtex0 and shadowtex1 to carry hardware depth comparison
    // themselves, rather than through the *HW aliases
    public boolean hasFeature(com.bdmajora.impetus.umbra.features.FeatureFlags feature) {
        return this.activeFeatures.contains(feature);
    }

    // Every text file, by path
    public Map<AbsolutePackPath, String> getSources() {
        return this.sources;
    }

    // Reads each program's stages, applying includes and option edits
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

    // Reads and flattens one program's stages by source name
    // Null when the pack has neither a raster stage nor any compute stage for it anywhere — which is how a program
    // the pack simply does not ship is distinguished from one that failed to parse
    private ProgramSource readProgram(String sourceName) {
        String vertex = readStage(sourceName, "vsh");
        String fragment = readStage(sourceName, "fsh");
        String[] computes = readComputeVariants(sourceName);
        if (vertex == null && fragment == null && computes.length == 0) {
            return null;
        }
        String geometry = readStage(sourceName, "gsh");
        String tessControl = readStage(sourceName, "tcs");
        String tessEval = readStage(sourceName, "tes");
        return new ProgramSource(sourceName, vertex, geometry, tessControl, tessEval, fragment, computes);
    }

    // Reads a program's compute stages: <name>.csh plus the letter-suffixed <name>_a.csh through _z.csh, which is
    // an Iris extension
    // The letter scan STOPS at the first missing suffix, matching Iris's readComputeArray — so a pack shipping _a
    // and _c but no _b gets only _a. That is deliberate rather than a bug: the suffixes are an ordered chain, and
    // running _c without _b would feed it inputs _b never produced
    // Returns an empty array when the program has no compute stage at all, and otherwise a 27-entry array which may
    // still contain nulls
    private String[] readComputeVariants(String sourceName) {
        String[] computes = new String[ProgramSource.MAX_COMPUTE_VARIANTS];
        boolean any = false;

        computes[0] = readStage(sourceName, "csh");
        any = computes[0] != null;

        for (int variant = 1; variant < computes.length; variant++) {
            computes[variant] = readStage(ProgramSource.computeVariantName(sourceName, variant), "csh");
            if (computes[variant] == null) {
                break;
            }
            any = true;
        }

        return any ? computes : new String[0];
    }

    // One stage file, or null when the pack has none
    private String readStage(String sourceName, String extension) {
        AbsolutePackPath path = locateStage(sourceName, extension);
        if (path == null) {
            return null;
        }
        return String.join("\n", this.includeProcessor.process(path));
    }

    // Looks in the overworld override directory FIRST and the pack root second, which is what makes a world0/
    // program shadow the root one rather than merely coexist with it
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
