package com.bdmajora.fulgor.api;

/**
 * Implemented by {@code RenderGlobal}, which collects the positions whose light changed and turns
 * them into chunk rebuilds.
 *
 * <p>Vanilla drains that collection from inside {@code updateClouds} and only when the chunk builder
 * is idle, which means a stream of light updates can keep it permanently deferred (MC-80966). Fulgor
 * drains it unconditionally from the client tick instead, and this interface is the hook that lets the
 * tick reach it.
 */
public interface LightUpdateProcessor {
    void fulgor$processLightUpdates();
}
