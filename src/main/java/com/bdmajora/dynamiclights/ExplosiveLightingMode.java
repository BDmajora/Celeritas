package com.bdmajora.dynamiclights;

// How a creeper's flash or a primed TNT's fuse translates into light
// SIMPLE holds one constant luminance for the whole fuse, so it schedules a rebuild once
// FANCY ramps the value, which looks right but re-lights the surrounding chunks every time the number changes —
// the cost difference between the two is entirely rebuild count, not shading
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
