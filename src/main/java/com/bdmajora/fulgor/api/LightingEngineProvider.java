package com.bdmajora.fulgor.api;

import com.bdmajora.fulgor.lighting.LightingEngine;

// Implemented by World (owns the engine) and Chunk (caches the reference so Chunk.getLightFor does not walk back through the world); public so other mods can flush pending updates before reading light
public interface LightingEngineProvider {
    LightingEngine fulgor$getLightingEngine();
}
