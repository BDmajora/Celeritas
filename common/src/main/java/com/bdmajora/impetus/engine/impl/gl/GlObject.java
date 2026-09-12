package com.bdmajora.impetus.engine.impl.gl;

// Base class for the engine's GL objects, hiding the raw handle behind an accessor that checks validity first
// The check catches the common mistake — using an object after it was deleted — but it is not a guarantee: nothing
// stops a caller copying the int handle out and using that afterwards, so this is a guard rail rather than a
// safety property to lean on
public abstract class GlObject {
    private static final int INVALID_HANDLE = 0;

    private int handle = INVALID_HANDLE;

    protected GlObject() {

    }

    // Called once by the subclass constructor
    protected final void setHandle(int handle) {
        this.handle = handle;
    }

    // The GL name; throws once deleted
    public final int handle() {
        this.checkHandle();

        return this.handle;
    }

    // Throws on use after delete
    protected final void checkHandle() {
        if (!this.isHandleValid()) {
            throw new IllegalStateException("Handle is not valid");
        }
    }

    // Whether not yet deleted
    protected final boolean isHandleValid() {
        return this.handle != INVALID_HANDLE;
    }

    // Frees once; safe to call twice
    public final void delete() {
        this.destroyInternal();
        this.handle = INVALID_HANDLE;
    }

    // Alias of delete
    @Deprecated // kept around to avoid huge diffs in old Umbra code
    public final void destroy() {
        this.delete();
    }

    protected abstract void destroyInternal();
}
