package com.bdmajora.impetus.umbra.gl.uniform;

// One shader uniform bound to a program location, its value pulled from a supplier and uploaded on demand
// Each concrete subclass caches the last-uploaded value and only issues a glUniform* call when the value actually
// changed, matching OptiFine — which is what keeps redundant GL calls off the hot path, since update() runs for
// every uniform of every program bind
public abstract class Uniform {
    // Resolved once at program build; -1 would mean the uniform was optimised out, and the builder drops those
    // rather than constructing a Uniform that uploads into nowhere
    protected final int location;

    protected Uniform(int location) {
        this.location = location;
    }

    public final int getLocation() {
        return this.location;
    }

    // Re-reads the supplier and uploads if the value moved. Always into the CURRENTLY bound program, since
    // glUniform* has no program argument on 1.12.2's GL level — the caller owns making sure the right one is bound
    public abstract void update();
}
