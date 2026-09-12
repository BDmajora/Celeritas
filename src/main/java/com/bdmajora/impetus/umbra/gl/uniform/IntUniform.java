package com.bdmajora.impetus.umbra.gl.uniform;

import java.util.function.IntSupplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// A scalar int uniform pulled from a supplier every update; also the type sampler bindings use, since a sampler uniform is just the unit index
public class IntUniform extends Uniform {
    private final IntSupplier value;
    // Last value actually uploaded; an unchanged uniform then costs a supplier call instead of a GL call
    private int cachedValue;
    // Distinguishes "never uploaded" from "uploaded 0", since 0 is both the default and a real value (a sampler on unit 0)
    private boolean initialized;

    public IntUniform(int location, IntSupplier value) {
        super(location);
        this.value = value;
    }

    // Uploads into the currently bound program, so the caller must have bound it already
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
