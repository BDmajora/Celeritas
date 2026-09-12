package com.bdmajora.impetus.umbra.gl.uniform;

import org.joml.Vector3i;

import java.util.function.Supplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// An ivec3 uniform for Iris's cameraPositionInt, diffed against the last upload; cached is set in place since the supplier may mutate and return the same object, and initialized forces the first upload
public class Vector3IntUniform extends Uniform {
    private final Supplier<Vector3i> value;
    private final Vector3i cached = new Vector3i();
    private boolean initialized;

    public Vector3IntUniform(int location, Supplier<Vector3i> value) {
        super(location);
        this.value = value;
    }

    // Uploads only when the value changed
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
