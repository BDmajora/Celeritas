package com.bdmajora.impetus.umbra.uniforms.custom;

// The environment one custom-uniform expression is evaluated against
// Name lookup order matters: pack-defined variables and uniforms first (already evaluated earlier this frame, in
// declaration order), then the built-in input uniforms. A pack that names a variable after a built-in therefore
// shadows it, which is what packs expect
public interface CustomUniformContext {
    // Null for an unknown name rather than an exception: a pack referencing something this port does not provide
    // should drop that one uniform, not fail the whole pack
    CustomUniformValue resolve(String name);

    // Persistent state for one smooth() call site, keyed by an index the parser assigns per call
    // Per CALL rather than per uniform, because one expression can contain several smooth() calls and each needs
    // its own history — sharing a slot would make them interfere
    SmoothState smoothState(int index);

    // Seconds since the previous frame, so smooth() converges at a rate independent of framerate
    float frameTime();

    final class SmoothState {
        public boolean initialized;
        public float value;
    }
}
