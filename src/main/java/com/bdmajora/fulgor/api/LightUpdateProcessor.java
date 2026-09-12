package com.bdmajora.fulgor.api;

// Implemented by RenderGlobal; vanilla drains its light-update collection from updateClouds only when the chunk builder is idle (MC-80966), so Fulgor drains it unconditionally from the client tick via this hook
public interface LightUpdateProcessor {
    void fulgor$processLightUpdates();
}
