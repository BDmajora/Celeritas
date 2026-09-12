package com.bdmajora.impetus.umbra.gl.uniform;

import org.joml.Vector3f;

import java.util.function.Supplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// A vec3 uniform: camera position, sun and moon vectors, fog colour
// Diffed against the last upload, since update runs for every uniform of every bind and most do not move
// cached is set in place rather than stored by reference, since the supplier may mutate and return the same object
// initialized forces the first upload, which equals() against a fresh zero vector would otherwise skip
public class Vector3Uniform extends Uniform {
    private final Supplier<Vector3f> value;
    private final Vector3f cached = new Vector3f();
    private boolean initialized;

    public Vector3Uniform(int location, Supplier<Vector3f> value) {
        super(location);
        this.value = value;
    }

    // Uploads only when the value changed
    @Override
    public void update() {
        Vector3f newValue = this.value.get();
        if (!this.initialized || !this.cached.equals(newValue)) {
            this.initialized = true;
            this.cached.set(newValue);
            LWJGL.glUniform3f(this.location, newValue.x, newValue.y, newValue.z);
        }
    }
}
