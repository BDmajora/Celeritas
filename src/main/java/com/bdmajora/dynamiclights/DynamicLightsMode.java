package com.bdmajora.dynamiclights;

/**
 * How often a moving light source is allowed to re-light the chunks around it.
 *
 * <p>The delay is not a frame budget — it is a floor on how often one source may schedule a chunk
 * rebuild. Rebuilding a section is the expensive part of dynamic lighting, so a source that moves
 * every tick would otherwise queue eight sections' worth of work every tick.
 */
public enum DynamicLightsMode implements DynamicLightsConfig.Localized {
    OFF(0, "impetus.options.dynamiclights.mode.off"),
    SLOW(500, "impetus.options.dynamiclights.mode.slow"),
    FAST(250, "impetus.options.dynamiclights.mode.fast"),
    REALTIME(0, "impetus.options.dynamiclights.mode.realtime");

    private final int delay;
    private final String key;

    DynamicLightsMode(int delay, String key) {
        this.delay = delay;
        this.key = key;
    }

    public boolean isEnabled() {
        return this != OFF;
    }

    /** True when {@link #getDelay()} is a real floor rather than "every update". */
    public boolean hasDelay() {
        return this.delay != 0;
    }

    /** Minimum milliseconds between updates for a single source. */
    public int getDelay() {
        return this.delay;
    }

    @Override
    public String translationKey() {
        return this.key;
    }
}
