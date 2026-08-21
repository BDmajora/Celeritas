package com.bdmajora.fulgor.api;

import com.bdmajora.fulgor.lighting.LightingEngine;

/**
 * Implemented by {@code World} and by {@code Chunk}, both of which need to reach the engine.
 *
 * <p>The world owns its engine; a chunk caches the reference its world handed it at construction so
 * the hot path — {@code Chunk.getLightFor} — does not walk back through the world every call.
 *
 * <p>Part of Fulgor's public surface: another mod that maintains light itself can cast a
 * {@code World} to this and flush pending updates before reading light data directly.
 */
public interface LightingEngineProvider {
    LightingEngine fulgor$getLightingEngine();
}
