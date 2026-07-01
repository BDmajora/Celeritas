package org.taumc.celeritas.iris.gl.uniform;

import org.joml.Vector4f;

import java.util.function.Supplier;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/** A {@code vec4} uniform (e.g. OptiFine's {@code entityColor}). */
public class Vector4Uniform extends Uniform {
    private final Supplier<Vector4f> value;
    private final Vector4f cached = new Vector4f();
    private boolean initialized;

    public Vector4Uniform(int location, Supplier<Vector4f> value) {
        super(location);
        this.value = value;
    }

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
