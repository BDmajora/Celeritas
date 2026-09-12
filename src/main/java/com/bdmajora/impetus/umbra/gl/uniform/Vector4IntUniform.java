package com.bdmajora.impetus.umbra.gl.uniform;

import org.joml.Vector4i;

import java.util.function.Supplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// An ivec4 uniform
// Diffed against the last upload, since update runs for every uniform of every bind and most do not move
// cached is set in place rather than stored by reference, since the supplier may mutate and return the same object
// initialized forces the first upload, which equals() against a fresh zero vector would otherwise skip
public class Vector4IntUniform extends Uniform {
    private final Supplier<Vector4i> value;
    private final Vector4i cached = new Vector4i();
    private boolean initialized;

    public Vector4IntUniform(int location, Supplier<Vector4i> value) {
        super(location);
        this.value = value;
    }

    // Uploads only when the value changed
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
