package com.bdmajora.impetus.umbra.uniforms.custom;

// The environment one custom-uniform expression evaluates against; lookup order is pack variables and uniforms first (evaluated earlier this frame in declaration order), then built-ins, so a pack variable named after a built-in shadows it as packs expect
public interface CustomUniformContext {
    // Null for an unknown name rather than an exception: a pack referencing something this port lacks drops that one uniform, not the whole pack
    CustomUniformValue resolve(String name);

    // Persistent state for one smooth() call site, keyed by a parser-assigned index per CALL since one expression can contain several smooth() calls needing separate histories
    SmoothState smoothState(int index);

    // Seconds since the previous frame, so smooth() converges at a rate independent of framerate
    float frameTime();

    final class SmoothState {
        public boolean initialized;
        public float value;
    }
}
