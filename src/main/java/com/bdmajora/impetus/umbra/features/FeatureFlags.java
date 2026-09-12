package com.bdmajora.impetus.umbra.features;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// The Iris feature-flag vocabulary restricted to what this port honors; packs name these in shaders.properties as `iris.features.required`/`optional`, so the constant names are the pack-facing spelling and cannot be renamed
public enum FeatureFlags {
    // Implemented here, so declaring them is safe and the matching IRIS_FEATURE_* define is emitted
    CUSTOM_IMAGES(true),
    COMPUTE_SHADERS(true),
    SSBO(true),
    BLOCK_EMISSION_ATTRIBUTE(true),
    SEPARATE_HARDWARE_SAMPLERS(true),
    // Not implemented: a pack listing one of these under `required` is refused at load with a visible message rather than silently rendering wrong
    ENTITY_TRANSLUCENT(false),
    PER_BUFFER_BLENDING(false),
    HIGHER_SHADOWCOLOR(false),
    REVERSED_CULLING(false),
    CAN_DISABLE_WEATHER(false),
    TESSELLATION_SHADERS(false),
    // Catch-all for a token we do not recognise at all — a newer Iris flag, or a typo in the pack
    UNKNOWN(false);

    // Whether this port implements the flag; drives both the refusal check and the emitted defines
    private final boolean usable;

    FeatureFlags(boolean usable) {
        this.usable = usable;
    }

    // Whether this port implements the feature, so a pack requiring it can be refused up front
    public boolean isUsable() {
        return this.usable;
    }

    // Token -> constant, trimmed and upper-cased since packs write these loosely; unrecognised is UNKNOWN rather than an exception so a pack listing a newer Iris flag under `optional` still loads
    public static FeatureFlags byName(String name) {
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    // Names from a declaration list this port cannot honor, in the pack's own spelling so the error quotes the author; only called with the `required` list
    public static List<String> findUnsupported(String declared) {
        List<String> missing = new ArrayList<>();
        if (declared == null || declared.trim().isEmpty()) {
            return missing;
        }
        // Whitespace-separated, matching Iris's own parse of the property value
        for (String token : declared.trim().split("\\s+")) {
            if (!byName(token).isUsable()) {
                missing.add(token);
            }
        }
        return missing;
    }

    // Flags the pack asked for AND this port implements, from both lists; what ShaderPack.hasFeature answers, distinct from which IRIS_FEATURE_* defines exist
    public static java.util.Set<FeatureFlags> parseDeclared(String required, String optional) {
        java.util.Set<FeatureFlags> declared = java.util.EnumSet.noneOf(FeatureFlags.class);
        for (String list : new String[]{required, optional}) {
            if (list == null || list.trim().isEmpty()) {
                continue;
            }
            for (String token : list.trim().split("\\s+")) {
                FeatureFlags flag = byName(token);
                // UNKNOWN and unusable tokens drop out here; unusable `required` ones already failed the load
                if (flag != UNKNOWN && flag.isUsable()) {
                    declared.add(flag);
                }
            }
        }
        return declared;
    }

    // Emits one `IRIS_FEATURE_<NAME>` define per implemented flag; the prefix is Iris's, packs gate on it verbatim (Complementary's colored lighting), and it is advertised unconditionally since the lists are a contract, not a request
    public static void addUsableDefines(java.util.Map<String, String> macros) {
        for (FeatureFlags flag : values()) {
            if (flag != UNKNOWN && flag.usable) {
                macros.put("IRIS_FEATURE_" + flag.name(), "");
            }
        }
    }
}
