package com.bdmajora.impetus.api.config.theme;

import java.util.Objects;

public final class ConfigTheme {
    private final String modId;
    private final int accentColor;

    private ConfigTheme(String modId, int accentColor) {
        this.modId = modId;
        this.accentColor = accentColor;
    }

    public String getModId() {
        return this.modId;
    }

    public int getAccentColor() {
        return this.accentColor;
    }

    public static Builder builder(String modId) {
        return new Builder(modId);
    }

    public static final class Builder {
        private final String modId;
        private int accentColor = 0xFF00CBCB;

        private Builder(String modId) {
            this.modId = Objects.requireNonNull(modId, "Mod id must not be null");
        }

        public Builder accentColor(int color) {
            this.accentColor = color;
            return this;
        }

        public ConfigTheme build() {
            return new ConfigTheme(this.modId, this.accentColor);
        }
    }
}
