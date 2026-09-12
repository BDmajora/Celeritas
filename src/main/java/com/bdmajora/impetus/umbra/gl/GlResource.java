package com.bdmajora.impetus.umbra.gl;

// Base for a GL object owning one integer handle (shader, program, texture, FBO); subclasses free in destroyInternal, callers only touch destroy(), and every GL call goes through com.bdmajora.impetus.lwjgl
public abstract class GlResource {
    private int handle;
    // Latched by destroy(), which is what makes destroy() idempotent and getGlId() able to fail loudly
    private boolean destroyed;

    protected GlResource() {
        // -1 rather than 0, since 0 is a valid-looking "no object" name that would bind silently; -1 makes use-before-assignment a GL error naming an impossible object
        this.handle = -1;
    }

    // Called once by the subclass constructor
    protected final void setHandle(int handle) {
        this.handle = handle;
    }

    // Throws rather than returning the stale handle: the driver can REUSE a destroyed name, so use-after-destroy would silently operate on someone else's texture
    public final int getGlId() {
        if (this.destroyed) {
            throw new IllegalStateException("Tried to use a destroyed GL resource (" + getClass().getSimpleName() + ")");
        }
        return this.handle;
    }

    // Whether destroy has run
    public final boolean isDestroyed() {
        return this.destroyed;
    }

    // Idempotent, because teardown paths overlap (pack reload and pipeline destroy) and double-deleting a recycled GL name corrupts an unrelated object
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
