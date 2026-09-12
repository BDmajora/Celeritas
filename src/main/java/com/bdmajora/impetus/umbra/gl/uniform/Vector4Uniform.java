package com.bdmajora.impetus.umbra.gl.uniform;

import org.joml.Vector4f;

import java.util.function.Supplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// A vec4 uniform; OptiFine's entityColor is the common one, the hurt and flash tint plus strength
// Diffed against the last upload, since update runs for every uniform of every bind and most do not move
// cached is set in place rather than stored by reference, since the supplier may mutate and return the same object
// initialized forces the first upload, which equals() against a fresh zero vector would otherwise skip
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
