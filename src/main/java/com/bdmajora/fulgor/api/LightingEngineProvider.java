package com.bdmajora.fulgor.api;

import com.bdmajora.fulgor.lighting.LightingEngine;

// Implemented by World (owns the engine) and Chunk (caches the reference at construction so the
// hot path, Chunk.getLightFor, doesn't walk back through the world every call). Public API surface:
// other mods can cast a World to this and flush pending updates before reading light directly.
public interface LightingEngineProvider {
    LightingEngine fulgor$getLightingEngine();
}
