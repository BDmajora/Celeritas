package com.bdmajora.impetus.umbra.gl.uniform;

// How often a uniform's value has to be recomputed and re-uploaded
// The collector groups uniforms by this so a frame does not re-evaluate suppliers whose answer cannot have moved
public enum UniformUpdateFrequency {
    // Can change between program binds WITHIN one frame — render stage, fog mode, the per-object material ids.
    // Re-evaluated on every bind, which is why these are the ones worth keeping few
    DYNAMIC,
    // Constant for the lifetime of the program; uploaded the first time it is used and never again
    ONCE,
    // Changes at most once per game tick: moon phase, potion effects, weather strength
    PER_TICK,
    // Changes every frame: camera matrices, sun angle, the frame counter
    PER_FRAME
}
