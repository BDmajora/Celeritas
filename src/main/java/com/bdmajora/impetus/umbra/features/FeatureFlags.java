package com.bdmajora.impetus.umbra.features;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// The Iris feature-flag vocabulary, restricted to what this port can actually honor
// Packs name these in shaders.properties as `iris.features.required` / `iris.features.optional`, so the enum
// constant names are the pack-facing spelling and cannot be renamed to fit our internal "Umbra" naming
public enum FeatureFlags {
    // Implemented here, so declaring them is safe and the matching IRIS_FEATURE_* define is emitted
    CUSTOM_IMAGES(true),
    COMPUTE_SHADERS(true),
    SSBO(true),
    BLOCK_EMISSION_ATTRIBUTE(true),
    SEPARATE_HARDWARE_SAMPLERS(true),
    // Not implemented: a pack listing one of these under `required` is refused at load with a visible message
    // rather than silently rendering wrong, because required means the pack cannot work without it
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

    public boolean isUsable() {
        return this.usable;
    }

    // Token -> enum constant, trimmed and upper-cased because packs write these in any case with loose spacing
    // An unrecognised token is UNKNOWN rather than an exception: a pack listing a flag from a newer Iris under
    // `optional` must still load
    public static FeatureFlags byName(String name) {
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    // Names from a declaration list that this port cannot honor, in the pack's own spelling so the error message
    // quotes back exactly what the author wrote
    // Only ever called with the `required` list — unsupported `optional` flags are simply not advertised
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

    // The set of flags the loaded pack asked for AND this port implements, from both declaration lists at once
    // This is what ShaderPack.hasFeature answers from — it is about what the PACK opted into, which is not the
    // same question as which IRIS_FEATURE_* defines exist (addUsableDefines advertises everything we support)
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

    // Emits one `IRIS_FEATURE_<NAME>` define per implemented flag into the macro map every program compiles with
    // The IRIS_FEATURE_ prefix is Iris's, not ours: packs write `#ifdef IRIS_FEATURE_CUSTOM_IMAGES` verbatim
    // (that exact gate is what Complementary's colored lighting hangs off), so renaming the prefix silently drops
    // the pack onto its plain-OptiFine path
    // Advertised unconditionally rather than only for declared flags, because a pack may use a feature it never
    // listed — the lists are a compatibility contract, not a request
    public static void addUsableDefines(java.util.Map<String, String> macros) {
        for (FeatureFlags flag : values()) {
            if (flag != UNKNOWN && flag.usable) {
                macros.put("IRIS_FEATURE_" + flag.name(), "");
            }
        }
    }
}
