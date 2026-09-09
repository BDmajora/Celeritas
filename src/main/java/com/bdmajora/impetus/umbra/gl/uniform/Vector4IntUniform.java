package com.bdmajora.impetus.umbra.gl.uniform;

import org.joml.Vector4i;

import java.util.function.Supplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// An ivec4 uniform
// Diffed against the last-uploaded value rather than uploaded blind, because update() runs for every
// uniform of every program bind and most of these do not move between binds
// `cached` is a mutable instance set in place, not a stored reference: the supplier is free to hand back
// the same object it mutated, so holding its reference would compare it against itself and never upload
// `initialized` is what makes the all-zero first value upload — equals() against a fresh zero vector is
// true, so without it a uniform whose real value is (0,0,0) would never be written at all
public class Vector4IntUniform extends Uniform {
    private final Supplier<Vector4i> value;
    private final Vector4i cached = new Vector4i();
    private boolean initialized;

    public Vector4IntUniform(int location, Supplier<Vector4i> value) {
        super(location);
        this.value = value;
    }

    @Override
    public void update() {
        Vector4i newValue = this.value.get();
        if (!this.initialized || !this.cached.equals(newValue)) {
            this.initialized = true;
            this.cached.set(newValue);
            LWJGL.glUniform4i(this.location, newValue.x, newValue.y, newValue.z, newValue.w);
        }
    }
}
