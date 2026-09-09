package com.bdmajora.impetus.umbra.gl;

// Base class for a GL object that owns one integer handle: a shader, a program, a texture, an FBO
// Subclasses free their own resource in destroyInternal; callers only ever touch destroy()
// Every GL call in a subclass must go through the com.bdmajora.impetus.lwjgl abstraction rather than raw
// org.lwjgl, which is what keeps the LWJGL2/LWJGL3 split working
public abstract class GlResource {
    private int handle;
    // Latched by destroy(), which is what makes destroy() idempotent and getGlId() able to fail loudly
    private boolean destroyed;

    protected GlResource() {
        // -1 rather than 0, because 0 is a valid-looking GL name meaning "no object" and would be bound silently;
        // -1 makes a use-before-assignment show up as a GL error naming an impossible object
        this.handle = -1;
    }

    protected final void setHandle(int handle) {
        this.handle = handle;
    }

    // Throws rather than returning the stale handle: a destroyed handle can be REUSED by the driver for an
    // unrelated object, so a use-after-destroy would silently operate on someone else's texture
    public final int getGlId() {
        if (this.destroyed) {
            throw new IllegalStateException("Tried to use a destroyed GL resource (" + getClass().getSimpleName() + ")");
        }
        return this.handle;
    }

    public final boolean isDestroyed() {
        return this.destroyed;
    }

    // Idempotent, because teardown paths overlap: a pack reload and a pipeline destroy can both reach the same
    // resource, and double-deleting a GL name the driver has already recycled corrupts an unrelated object
    public final void destroy() {
        if (this.destroyed) {
            return;
        }
        destroyInternal();
        this.destroyed = true;
        this.handle = -1;
    }

    protected abstract void destroyInternal();
}
