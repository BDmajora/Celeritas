package com.bdmajora.impetus.umbra.gl.uniform;

import org.joml.Vector2i;

import java.util.function.Supplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// An ivec2 uniform; OptiFine's eyeBrightness is the one that matters
// Diffed against the last upload, since update runs for every uniform of every bind and most do not move
// cached is set in place rather than stored by reference, since the supplier may mutate and return the same object
// initialized forces the first upload, which equals() against a fresh zero vector would otherwise skip
public class Vector2IntUniform extends Uniform {
    private final Supplier<Vector2i> value;
    private final Vector2i cached = new Vector2i();
    private boolean initialized;

    public Vector2IntUniform(int location, Supplier<Vector2i> value) {
        super(location);
        this.value = value;
    }

    // Uploads only when the value changed
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
