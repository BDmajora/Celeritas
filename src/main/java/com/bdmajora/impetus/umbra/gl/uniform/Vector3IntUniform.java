package com.bdmajora.impetus.umbra.gl.uniform;

import org.joml.Vector3i;

import java.util.function.Supplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// An ivec3 uniform for Iris's cameraPositionInt, the integer half of the split camera position
// Diffed against the last upload, since update runs for every uniform of every bind and most do not move
// cached is set in place rather than stored by reference, since the supplier may mutate and return the same object
// initialized forces the first upload, which equals() against a fresh zero vector would otherwise skip
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
