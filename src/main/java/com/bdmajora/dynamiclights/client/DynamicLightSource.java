package com.bdmajora.dynamiclights.client;

import com.bdmajora.dynamiclights.DynamicLights;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.world.World;

/**
 * Something that emits light the world does not know about.
 *
 * <p>Implemented by mixins onto {@code Entity} and {@code TileEntity}, so every entity and block
 * entity in the game is one of these — {@link #impetus$getLuminance()} returning zero is how the
 * overwhelming majority of them opt out.
 */
public interface DynamicLightSource {
    double impetus$getDynamicLightX();

    double impetus$getDynamicLightY();

    double impetus$getDynamicLightZ();

    /** The world this source lives in, or null for a block entity that has not been placed. */
    World impetus$getDynamicLightWorld();

    /** Whether the engine is currently tracking this source. */
    default boolean impetus$isDynamicLightEnabled() {
        return DynamicLights.options().mode.isEnabled()
                && DynamicLights.engine().containsLightSource(this);
    }

    /**
     * Starts or stops tracking this source.
     *
     * <p>Internal: called by {@link DynamicLightsEngine#updateTracking} as luminance crosses zero.
     * Calling it directly will desynchronise the tracked set from the sources' own state.
     */
    default void impetus$setDynamicLightEnabled(boolean enabled) {
        this.impetus$resetDynamicLight();
        if (enabled) {
            DynamicLights.engine().addLightSource(this);
        } else {
            DynamicLights.engine().removeLightSource(this);
        }
    }

    /** Forgets the last luminance, so the next update is treated as a change. */
    void impetus$resetDynamicLight();

    /** Luminance in the vanilla 0-15 scale; values below 1 are ignored. */
    int impetus$getLuminance();

    /** Recomputes {@link #impetus$getLuminance()}. Called once per tick while the source is alive. */
    void impetus$dynamicLightTick();

    /** Whether the configured update delay has elapsed for this source. */
    boolean impetus$shouldUpdateDynamicLight();

    /**
     * Re-lights the chunks around this source if it has moved or changed brightness.
     *
     * @return true if a rebuild was scheduled
     */
    boolean impetus$updateDynamicLight(RenderGlobal renderer);

    /** Queues a rebuild of every chunk this source is currently lighting. */
    void impetus$scheduleTrackedChunksRebuild(RenderGlobal renderer);
}
