package com.bdmajora.impetus.iris.features;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Iris feature-flag vocabulary ({@code iris.features.required} / {@code iris.features.optional} in
 * {@code shaders.properties}). Each flag knows whether this 1.12.2 port can actually honor it; usable flags are
 * advertised to shaders as {@code IRIS_FEATURE_<NAME>} defines, and packs *requiring* an unusable flag produce a
 * loud, user-visible error instead of silently broken rendering.
 */
public enum FeatureFlags {
    CUSTOM_IMAGES(true),
    COMPUTE_SHADERS(true),
    SSBO(true),
    BLOCK_EMISSION_ATTRIBUTE(true),
    SEPARATE_HARDWARE_SAMPLERS(true),
    // Not (yet) honored by this port — packs requiring these get a visible error rather than broken visuals.
    ENTITY_TRANSLUCENT(false),
    PER_BUFFER_BLENDING(false),
    HIGHER_SHADOWCOLOR(false),
    REVERSED_CULLING(false),
    CAN_DISABLE_WEATHER(false),
    TESSELLATION_SHADERS(false),
    UNKNOWN(false);

    private final boolean usable;

    FeatureFlags(boolean usable) {
        this.usable = usable;
    }

    public boolean isUsable() {
        return this.usable;
    }

    public static FeatureFlags byName(String name) {
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /** {@return the names from {@code declared} (space-separated) that this port cannot honor} */
    public static List<String> findUnsupported(String declared) {
        List<String> missing = new ArrayList<>();
        if (declared == null || declared.trim().isEmpty()) {
            return missing;
        }
        for (String token : declared.trim().split("\\s+")) {
            if (!byName(token).isUsable()) {
                missing.add(token);
            }
        }
        return missing;
    }

    /**
     * The usable flags a pack actually declared, across {@code iris.features.required} and
     * {@code iris.features.optional} (both space-separated; either may be null).
     * <p>
     * Iris keeps this set deliberately separate from the {@code IRIS_FEATURE_<NAME>} defines and gates pipeline
     * behaviour on it, so a pack that never asked for a feature keeps the pre-feature behaviour even on an
     * implementation that could provide it.
     */
    public static java.util.Set<FeatureFlags> parseDeclared(String required, String optional) {
        java.util.Set<FeatureFlags> declared = java.util.EnumSet.noneOf(FeatureFlags.class);
        for (String list : new String[]{required, optional}) {
            if (list == null || list.trim().isEmpty()) {
                continue;
            }
            for (String token : list.trim().split("\\s+")) {
                FeatureFlags flag = byName(token);
                if (flag != UNKNOWN && flag.isUsable()) {
                    declared.add(flag);
                }
            }
        }
        return declared;
    }

    /** Adds an {@code IRIS_FEATURE_<NAME>} define for every flag this port can honor. */
    public static void addUsableDefines(java.util.Map<String, String> macros) {
        for (FeatureFlags flag : values()) {
            if (flag != UNKNOWN && flag.usable) {
                macros.put("IRIS_FEATURE_" + flag.name(), "");
            }
        }
    }
}
