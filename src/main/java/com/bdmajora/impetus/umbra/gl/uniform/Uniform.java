package com.bdmajora.impetus.umbra.gl.uniform;

// One uniform bound to a program location, pulled from a supplier and uploaded on demand; subclasses cache the last value and only upload on change like OptiFine, since update() runs for every uniform of every bind
public abstract class Uniform {
    // Resolved once at program build; -1 would mean optimised out, and the builder drops those rather than uploading into nowhere
    protected final int location;

    protected Uniform(int location) {
        this.location = location;
    }

    // glGetUniformLocation result, resolved at build time
    public final int getLocation() {
        return this.location;
    }

    // Re-reads the supplier and uploads if the value moved, always into the CURRENTLY bound program since glUniform* has no program argument at this GL level
    public abstract void update();
}
