package com.bdmajora.dynamiclights;

/**
 * How a creeper's flash or a primed TNT's fuse translates into light.
 *
 * <p>{@link #SIMPLE} holds a constant luminance for the whole fuse; {@link #FANCY} ramps it, which
 * looks right but re-lights the surrounding chunks every time the value changes.
 */
public enum ExplosiveLightingMode implements DynamicLightsConfig.Localized {
    OFF("impetus.options.dynamiclights.explosive.off"),
    SIMPLE("impetus.options.dynamiclights.explosive.simple"),
    FANCY("impetus.options.dynamiclights.explosive.fancy");

    private final String key;

    ExplosiveLightingMode(String key) {
        this.key = key;
    }

    public boolean isEnabled() {
        return this != OFF;
    }

    @Override
    public String translationKey() {
        return this.key;
    }
}
