package com.bdmajora.impetus.api.config.theme;

import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

public final class ConfigThemeRegistry {
    private static final Map<String, ConfigTheme> THEMES = new ConcurrentHashMap<>();

    private ConfigThemeRegistry() {
    }

    public static void register(ConfigTheme theme) {
        THEMES.put(theme.getModId(), theme);
    }

    public static OptionalInt getAccentColor(String modId) {
        ConfigTheme theme = THEMES.get(modId);
        return theme != null ? OptionalInt.of(theme.getAccentColor()) : OptionalInt.empty();
    }
}
