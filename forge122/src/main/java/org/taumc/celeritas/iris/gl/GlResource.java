package org.taumc.celeritas.iris.gl;

/**
 * Base class for a GL object that owns a single integer handle (a shader, a program, a texture, an FBO, …).
 * <p>
 * All GL calls go through Celeritas's {@code org.taumc.celeritas.lwjgl} abstraction (never raw {@code org.lwjgl}),
 * which is what keeps the LWJGL2/LWJGL3 split working. Subclasses implement {@link #destroyInternal()} to free their
 * specific GL resource; callers invoke {@link #destroy()} exactly once.
 */
public abstract class GlResource {
    private int handle;
    private boolean destroyed;

    protected GlResource() {
        this.handle = -1;
    }

    protected final void setHandle(int handle) {
        this.handle = handle;
    }

    public final int getGlId() {
        if (this.destroyed) {
            throw new IllegalStateException("Tried to use a destroyed GL resource (" + getClass().getSimpleName() + ")");
        }
        return this.handle;
    }

    public final boolean isDestroyed() {
        return this.destroyed;
    }

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
