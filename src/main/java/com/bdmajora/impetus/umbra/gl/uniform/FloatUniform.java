package com.bdmajora.impetus.umbra.gl.uniform;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/** A scalar {@code float} uniform. */
public class FloatUniform extends Uniform {
    private final FloatSupplier value;
    private float cachedValue;
    private boolean initialized;

    public FloatUniform(int location, FloatSupplier value) {
        super(location);
        this.value = value;
    }

    @Override
    public void update() {
        float newValue = this.value.getAsFloat();
        if (!this.initialized || newValue != this.cachedValue) {
            this.initialized = true;
            this.cachedValue = newValue;
            LWJGL.glUniform1f(this.location, newValue);
        }
    }
}
