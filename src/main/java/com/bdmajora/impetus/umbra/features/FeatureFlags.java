package com.bdmajora.impetus.umbra.features;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Supported Umbra feature flags for this port. */
public enum FeatureFlags {
    CUSTOM_IMAGES(true),
    COMPUTE_SHADERS(true),
    SSBO(true),
    BLOCK_EMISSION_ATTRIBUTE(true),
    SEPARATE_HARDWARE_SAMPLERS(true),
    // Unsupported required flags fail visibly.
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

    /** Returns unsupported declared flag names. */
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

    /** Returns declared usable flags. */
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

    /** Adds defines for usable flags. */
    public static void addUsableDefines(java.util.Map<String, String> macros) {
        for (FeatureFlags flag : values()) {
            if (flag != UNKNOWN && flag.usable) {
                macros.put("UMBRA_FEATURE_" + flag.name(), "");
            }
        }
    }
}
