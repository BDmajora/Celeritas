package com.bdmajora.impetus.iris.gl.framebuffer;

import com.bdmajora.impetus.iris.gl.GlResource;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL30;
import com.bdmajora.impetus.lwjgl.MemoryStack;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

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
    private final Map<Integer, Integer> logicalAttachmentPoints = new HashMap<>();
    private final Map<Integer, Integer> attachmentLogicalIndices = new HashMap<>();
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
        if (logicalIndex < 0) {
            throw new IllegalArgumentException("Logical color attachment index must be non-negative: " + logicalIndex);
        }
        validateColorAttachmentIndex(attachmentIndex);
        Integer previousAttachment = this.logicalAttachmentPoints.get(logicalIndex);
        if (previousAttachment != null && previousAttachment != attachmentIndex) {
            throw new IllegalArgumentException("Logical color attachment index " + logicalIndex
                    + " is already bound to physical attachment " + previousAttachment);
        }
        Integer previousLogical = this.attachmentLogicalIndices.get(attachmentIndex);
        if (previousLogical != null && previousLogical != logicalIndex) {
            throw new IllegalArgumentException("Physical color attachment " + attachmentIndex
                    + " is already bound to logical colortex" + previousLogical);
        }
        bind();
        LWJGL.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + attachmentIndex,
                GL11.GL_TEXTURE_2D, texture, 0);
        this.colorAttachments.put(logicalIndex, texture);
        this.logicalAttachmentPoints.put(logicalIndex, attachmentIndex);
        this.attachmentLogicalIndices.put(attachmentIndex, logicalIndex);
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

    /**
     * Sets the draw-buffer mask from color attachment indices. A negative entry disables that output slot with
     * {@code GL_NONE}, preserving the shader's dense slot numbering when an optional target is unavailable.
     */
    public void drawBuffers(int[] colorIndices) {
        if (colorIndices == null) {
            colorIndices = new int[0];
        }
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
            boolean[] seen = new boolean[this.maxColorAttachments];
            for (int colorIndex : colorIndices) {
                if (colorIndex < 0) {
                    buffer.put(GL11.GL_NONE);
                    continue;
                }
                validateColorAttachmentIndex(colorIndex);
                if (seen[colorIndex]) {
                    throw new IllegalArgumentException("Color attachment index " + colorIndex
                            + " appears more than once in one draw-buffer mask");
                }
                seen[colorIndex] = true;
                validateAttachedColorAttachmentIndex(colorIndex);
                buffer.put(GL30.GL_COLOR_ATTACHMENT0 + colorIndex);
            }
            buffer.flip();
            LWJGL.glDrawBuffers(buffer);
        }
    }

    public void readBuffer(int colorIndex) {
        validateColorAttachmentIndex(colorIndex);
        validateAttachedColorAttachmentIndex(colorIndex);
        bind();
        LWJGL.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0 + colorIndex);
    }

    private void validateColorAttachmentIndex(int colorIndex) {
        if (colorIndex < 0) {
            throw new IllegalArgumentException("Color attachment index must be non-negative: " + colorIndex);
        }
        if (colorIndex >= this.maxColorAttachments) {
            throw new IllegalArgumentException("Color attachment index " + colorIndex
                    + " exceeds GPU limit of " + this.maxColorAttachments);
        }
    }

    private void validateAttachedColorAttachmentIndex(int colorIndex) {
        if (!this.attachmentLogicalIndices.containsKey(colorIndex)) {
            throw new IllegalArgumentException("No color texture is attached to physical color attachment " + colorIndex);
        }
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
