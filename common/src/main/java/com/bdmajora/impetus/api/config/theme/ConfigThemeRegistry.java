package com.bdmajora.impetus.api.config.theme;

import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

public final class ConfigThemeRegistry {
    private static final Map<String, ConfigTheme> THEMES = new ConcurrentHashMap<>();

    private ConfigThemeRegistry() {
    }

    // Later registrations replace earlier
    public static void register(ConfigTheme theme) {
        THEMES.put(theme.getModId(), theme);
    }

    // Empty when the mod registered no theme
    public static OptionalInt getAccentColor(String modId) {
        ConfigTheme theme = THEMES.get(modId);
        return theme != null ? OptionalInt.of(theme.getAccentColor()) : OptionalInt.empty();
    }
}
