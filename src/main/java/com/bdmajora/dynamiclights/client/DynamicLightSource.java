package com.bdmajora.dynamiclights.client;

import com.bdmajora.dynamiclights.DynamicLights;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.world.World;

// Something that emits light the world does not know about
// Implemented by mixins onto Entity and TileEntity, so every entity/block entity is one of these; getLuminance() returning zero is how most opt out
public interface DynamicLightSource {
    double impetus$getDynamicLightX();

    double impetus$getDynamicLightY();

    double impetus$getDynamicLightZ();

    // World this source lives in, or null for a block entity that has not been placed
    World impetus$getDynamicLightWorld();

    // Whether the engine is currently tracking this source
    default boolean impetus$isDynamicLightEnabled() {
        return DynamicLights.options().mode.isEnabled()
                && DynamicLights.engine().containsLightSource(this);
    }

    // Starts or stops tracking this source; called internally by DynamicLightsEngine#updateTracking as luminance crosses zero
    // Calling directly will desynchronise the tracked set from the sources' own state
    default void impetus$setDynamicLightEnabled(boolean enabled) {
        this.impetus$resetDynamicLight();
        if (enabled) {
            DynamicLights.engine().addLightSource(this);
        } else {
            DynamicLights.engine().removeLightSource(this);
        }
    }

    // Forgets the last luminance, so the next update is treated as a change
    void impetus$resetDynamicLight();

    // Luminance in the vanilla 0-15 scale; values below 1 are ignored
    int impetus$getLuminance();

    // Recomputes getLuminance(); called once per tick while the source is alive
    void impetus$dynamicLightTick();

    // Whether the configured update delay has elapsed for this source
    boolean impetus$shouldUpdateDynamicLight();

    // Re-lights the chunks around this source if it has moved or changed brightness; returns true if a rebuild was scheduled
    boolean impetus$updateDynamicLight(RenderGlobal renderer);

    // Queues a rebuild of every chunk this source is currently lighting
    void impetus$scheduleTrackedChunksRebuild(RenderGlobal renderer);
}
