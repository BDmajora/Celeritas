package com.bdmajora.impetus.iris.gl.uniform;

import org.joml.Vector4i;

import java.util.function.Supplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/** An {@code ivec4} uniform. */
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
