package com.bdmajora.impetus.umbra.gl.uniform;

import org.joml.Vector4f;

import java.util.function.Supplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// A vec4 uniform (OptiFine's entityColor, hurt/flash tint plus strength), diffed against the last upload; cached is set in place since the supplier may mutate and return the same object, and initialized forces the first upload
public class Vector4Uniform extends Uniform {
    private final Supplier<Vector4f> value;
    private final Vector4f cached = new Vector4f();
    private boolean initialized;

    public Vector4Uniform(int location, Supplier<Vector4f> value) {
        super(location);
        this.value = value;
    }

    // Uploads only when the value changed
    @Override
    public void update() {
        Vector4f newValue = this.value.get();
        if (!this.initialized || !this.cached.equals(newValue)) {
            this.initialized = true;
            this.cached.set(newValue);
            LWJGL.glUniform4f(this.location, newValue.x, newValue.y, newValue.z, newValue.w);
        }
    }
}
