package org.taumc.celeritas.iris.gl.uniform;

import java.util.function.IntSupplier;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/** A scalar {@code int} uniform. */
public class IntUniform extends Uniform {
    private final IntSupplier value;
    private int cachedValue;
    private boolean initialized;

    public IntUniform(int location, IntSupplier value) {
        super(location);
        this.value = value;
    }

    @Override
    public void update() {
        int newValue = this.value.getAsInt();
        if (!this.initialized || newValue != this.cachedValue) {
            this.initialized = true;
            this.cachedValue = newValue;
            LWJGL.glUniform1i(this.location, newValue);
        }
    }
}
