package com.bdmajora.impetus.umbra.gl.uniform;

import org.joml.Vector3i;

import java.util.function.Supplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// An ivec3 uniform. Iris's cameraPositionInt is the reason it exists: the integer half of the split camera
// position, which packs recombine with the fractional part to dodge float precision loss far from origin
// Diffed against the last-uploaded value rather than uploaded blind, because update() runs for every
// uniform of every program bind and most of these do not move between binds
// `cached` is a mutable instance set in place, not a stored reference: the supplier is free to hand back
// the same object it mutated, so holding its reference would compare it against itself and never upload
// `initialized` is what makes the all-zero first value upload — equals() against a fresh zero vector is
// true, so without it a uniform whose real value is (0,0,0) would never be written at all
public class Vector3IntUniform extends Uniform {
    private final Supplier<Vector3i> value;
    private final Vector3i cached = new Vector3i();
    private boolean initialized;

    public Vector3IntUniform(int location, Supplier<Vector3i> value) {
        super(location);
        this.value = value;
    }

    @Override
    public void update() {
        Vector3i newValue = this.value.get();
        if (!this.initialized || !this.cached.equals(newValue)) {
            this.initialized = true;
            this.cached.set(newValue);
            LWJGL.glUniform3i(this.location, newValue.x, newValue.y, newValue.z);
        }
    }
}
