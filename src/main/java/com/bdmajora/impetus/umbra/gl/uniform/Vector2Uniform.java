package com.bdmajora.impetus.umbra.gl.uniform;

import org.joml.Vector2f;

import java.util.function.Supplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/** A {@code vec2} uniform. */
public class Vector2Uniform extends Uniform {
    private final Supplier<Vector2f> value;
    private final Vector2f cached = new Vector2f();
    private boolean initialized;

    public Vector2Uniform(int location, Supplier<Vector2f> value) {
        super(location);
        this.value = value;
    }

    @Override
    public void update() {
        Vector2f newValue = this.value.get();
        if (!this.initialized || !this.cached.equals(newValue)) {
            this.initialized = true;
            this.cached.set(newValue);
            LWJGL.glUniform2f(this.location, newValue.x, newValue.y);
        }
    }
}
