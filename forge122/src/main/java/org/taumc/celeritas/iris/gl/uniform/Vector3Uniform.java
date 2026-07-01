package org.taumc.celeritas.iris.gl.uniform;

import org.joml.Vector3f;

import java.util.function.Supplier;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/** A {@code vec3} uniform (positions, colors, directions). */
public class Vector3Uniform extends Uniform {
    private final Supplier<Vector3f> value;
    private final Vector3f cached = new Vector3f();
    private boolean initialized;

    public Vector3Uniform(int location, Supplier<Vector3f> value) {
        super(location);
        this.value = value;
    }

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
