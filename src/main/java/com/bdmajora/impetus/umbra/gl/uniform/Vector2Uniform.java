package com.bdmajora.impetus.umbra.gl.uniform;

import org.joml.Vector2f;

import java.util.function.Supplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// A vec2 uniform: screen and texture sizes, the two-component pack constants
// Diffed against the last upload, since update runs for every uniform of every bind and most do not move
// cached is set in place rather than stored by reference, since the supplier may mutate and return the same object
// initialized forces the first upload, which equals() against a fresh zero vector would otherwise skip
public class Vector2Uniform extends Uniform {
    private final Supplier<Vector2f> value;
    private final Vector2f cached = new Vector2f();
    private boolean initialized;

    public Vector2Uniform(int location, Supplier<Vector2f> value) {
        super(location);
        this.value = value;
    }

    // Uploads only when the value changed
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
