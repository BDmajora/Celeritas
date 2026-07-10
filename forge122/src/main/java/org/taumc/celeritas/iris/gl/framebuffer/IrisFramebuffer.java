package org.taumc.celeritas.iris.gl.framebuffer;

import org.taumc.celeritas.iris.gl.GlResource;
import org.taumc.celeritas.lwjgl.GL11;
import org.taumc.celeritas.lwjgl.GL30;
import org.taumc.celeritas.lwjgl.MemoryStack;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * An Iris-owned framebuffer object used by the shader pipeline (gbuffer, shadow, composite, and final passes). This is
 * deliberately <em>not</em> {@code net.minecraft.client.renderer.Framebuffer} — vanilla's wrapper only supports a
 * single color + depth attachment, while shader packs need multiple logical color attachments with independent
 * draw-buffer masks. Logical colortex indices may be packed onto different physical attachment points.
 * <p>
 * All operations go through the LWJGL abstraction. Because the abstraction exposes no DSA entry points, attachment and
 * draw/read-buffer changes bind this FBO to {@code GL_FRAMEBUFFER} as a side effect.
 */
public class IrisFramebuffer extends GlResource {
    private final Map<Integer, Integer> colorAttachments = new HashMap<>();
    private final int maxDrawBuffers;
    private final int maxColorAttachments;
    private boolean hasDepthAttachment;

    public IrisFramebuffer() {
        setHandle(LWJGL.glGenFramebuffers());
        this.maxDrawBuffers = LWJGL.glGetInteger(GL30.GL_MAX_DRAW_BUFFERS);
        this.maxColorAttachments = LWJGL.glGetInteger(GL30.GL_MAX_COLOR_ATTACHMENTS);
    }

    public void bind() {
        LWJGL.glBindFramebuffer(GL30.GL_FRAMEBUFFER, getGlId());
    }

    public void bindAsReadBuffer() {
        LWJGL.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, getGlId());
    }

    public void bindAsDrawBuffer() {
        LWJGL.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, getGlId());
    }

    public void addColorAttachment(int index, int texture) {
        addColorAttachment(index, index, texture);
    }

    public void addColorAttachment(int logicalIndex, int attachmentIndex, int texture) {
        bind();
        LWJGL.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + attachmentIndex,
                GL11.GL_TEXTURE_2D, texture, 0);
        this.colorAttachments.put(logicalIndex, texture);
    }

    public void addDepthAttachment(int texture) {
        bind();
        LWJGL.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, texture, 0);
        this.hasDepthAttachment = true;
    }

    public void noDrawBuffers() {
        bind();
        LWJGL.glDrawBuffers(GL11.GL_NONE);
    }

    /** Sets the draw-buffer mask from a list of color attachment indices (translated to {@code GL_COLOR_ATTACHMENTn}). */
    public void drawBuffers(int[] colorIndices) {
        if (colorIndices.length > this.maxDrawBuffers) {
            throw new IllegalArgumentException("Cannot write to more than " + this.maxDrawBuffers + " draw buffers on this GPU");
        }
        bind();
        if (colorIndices.length == 0) {
            LWJGL.glDrawBuffers(GL11.GL_NONE);
            return;
        }
        try (MemoryStack stack = LWJGL.stackPush()) {
            IntBuffer buffer = stack.mallocInt(colorIndices.length);
            for (int colorIndex : colorIndices) {
                if (colorIndex >= this.maxColorAttachments) {
                    throw new IllegalArgumentException("Color attachment index " + colorIndex
                            + " exceeds GPU limit of " + this.maxColorAttachments);
                }
                buffer.put(GL30.GL_COLOR_ATTACHMENT0 + colorIndex);
            }
            buffer.flip();
            LWJGL.glDrawBuffers(buffer);
        }
    }

    public void readBuffer(int colorIndex) {
        bind();
        LWJGL.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0 + colorIndex);
    }

    public int getColorAttachment(int index) {
        Integer texture = this.colorAttachments.get(index);
        return texture == null ? 0 : texture;
    }

    public boolean hasDepthAttachment() {
        return this.hasDepthAttachment;
    }

    public int getStatus() {
        bind();
        return LWJGL.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
    }

    public boolean isComplete() {
        return getStatus() == GL30.GL_FRAMEBUFFER_COMPLETE;
    }

    @Override
    protected void destroyInternal() {
        LWJGL.glDeleteFramebuffers(getGlId());
    }
}
