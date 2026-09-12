package com.bdmajora.impetus.umbra.gl.uniform;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// A scalar float uniform, pulled from a supplier every update
public class FloatUniform extends Uniform {
    // Read fresh each update rather than pushed in, so the pipeline never has to know when the value moved
    private final FloatSupplier value;
    // Last value actually uploaded, so an unchanged uniform costs a supplier call instead of a GL call
    private float cachedValue;
    // Separate from cachedValue because 0.0f is a legitimate value; otherwise the first upload of a zero is skipped and the uniform keeps its linked default
    private boolean initialized;

    public FloatUniform(int location, FloatSupplier value) {
        super(location);
        this.value = value;
    }

    // Uploads into the currently bound program, so the caller must have bound it already
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
