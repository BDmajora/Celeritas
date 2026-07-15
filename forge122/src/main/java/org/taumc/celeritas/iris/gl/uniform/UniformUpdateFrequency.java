package org.taumc.celeritas.iris.gl.uniform;

/**
 * How often a uniform's value needs to be recomputed and re-uploaded.
 * <ul>
 *     <li>{@link #DYNAMIC} — may change between program binds inside one frame (e.g. render stage, fog mode).</li>
 *     <li>{@link #ONCE} — constant for the lifetime of the program (uploaded the first time it is used).</li>
 *     <li>{@link #PER_TICK} — changes at most once per game tick (e.g. moon phase, potion effects).</li>
 *     <li>{@link #PER_FRAME} — changes every frame (e.g. camera matrices, sun angle, frame counter).</li>
 * </ul>
 */
public enum UniformUpdateFrequency {
    DYNAMIC,
    ONCE,
    PER_TICK,
    PER_FRAME
}
