package org.taumc.celeritas.iris.gl.uniform;

import org.joml.Vector2i;

import java.util.function.Supplier;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/** An {@code ivec2} uniform (e.g. OptiFine's {@code eyeBrightness}). */
public class Vector2IntUniform extends Uniform {
    private final Supplier<Vector2i> value;
    private final Vector2i cached = new Vector2i();
    private boolean initialized;

    public Vector2IntUniform(int location, Supplier<Vector2i> value) {
        super(location);
        this.value = value;
    }

    @Override
    public void update() {
        Vector2i newValue = this.value.get();
        if (!this.initialized || !this.cached.equals(newValue)) {
            this.initialized = true;
            this.cached.set(newValue);
            LWJGL.glUniform2i(this.location, newValue.x, newValue.y);
        }
    }
}
