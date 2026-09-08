package com.bdmajora.fulgor.api;

// Implemented by RenderGlobal. Vanilla only drains its light-update collection from updateClouds when
// the chunk builder is idle, so a steady stream of updates can defer it forever (MC-80966); Fulgor
// drains it unconditionally from the client tick instead, through this hook.
public interface LightUpdateProcessor {
    void fulgor$processLightUpdates();
}
