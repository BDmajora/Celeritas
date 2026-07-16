package com.bdmajora.impetus.iris.gl.uniform;

/**
 * A single shader uniform bound to a program location, with a value pulled from a supplier and uploaded on demand.
 * <p>
 * Each concrete subclass caches the last-uploaded value and only issues a {@code glUniform*} call when the value
 * actually changes (matching OptiFine's behaviour), which keeps redundant GL calls out of the hot path.
 */
public abstract class Uniform {
    protected final int location;

    protected Uniform(int location) {
        this.location = location;
    }

    public final int getLocation() {
        return this.location;
    }

    public abstract void update();
}
