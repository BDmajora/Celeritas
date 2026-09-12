package com.bdmajora.impetus.umbra.gl.framebuffer;

import com.bdmajora.impetus.umbra.gl.GlResource;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL30;
import com.bdmajora.impetus.lwjgl.MemoryStack;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// A framebuffer owned by the shader pipeline, not vanilla's single-attachment Framebuffer, since a pack needs
// many colour attachments with independent draw-buffer masks. Logical colortex indices map onto whichever
// attachment slots are free. No DSA in the LWJGL abstraction, so every call binds this FBO as a side effect
public class UmbraFramebuffer extends GlResource {
    private final Map<Integer, Integer> colorAttachments = new HashMap<>();
    private final Map<Integer, Integer> logicalAttachmentPoints = new HashMap<>();
    private final Map<Integer, Integer> attachmentLogicalIndices = new HashMap<>();
    private final int maxDrawBuffers;
    private final int maxColorAttachments;
    private boolean hasDepthAttachment;

    public UmbraFramebuffer() {
        setHandle(LWJGL.glGenFramebuffers());
        this.maxDrawBuffers = LWJGL.glGetInteger(GL30.GL_MAX_DRAW_BUFFERS);
        this.maxColorAttachments = LWJGL.glGetInteger(GL30.GL_MAX_COLOR_ATTACHMENTS);
    }

    // GL_FRAMEBUFFER
    public void bind() {
        LWJGL.glBindFramebuffer(GL30.GL_FRAMEBUFFER, getGlId());
    }

    // GL_READ_FRAMEBUFFER, for blits
    public void bindAsReadBuffer() {
        LWJGL.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, getGlId());
    }

    // GL_DRAW_FRAMEBUFFER, for blits
    public void bindAsDrawBuffer() {
        LWJGL.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, getGlId());
    }

    // Attaches at the next free slot
    public void addColorAttachment(int index, int texture) {
        addColorAttachment(index, index, texture);
    }

    // Attaches at a specific slot and records the logical mapping
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

    // One depth texture; replaces any previous
    public void addDepthAttachment(int texture) {
        bind();
        LWJGL.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, texture, 0);
        this.hasDepthAttachment = true;
    }

    // Narrows the FBO's LIVE colour attachments to exactly the given logical set: everything else previously added
    // is physically detached, and everything in the set is re-attached from the stored texture map
    // This mirrors Iris, which gives each gbuffer program a framebuffer holding only the buffers that program
    // writes, and it is a correctness requirement rather than tidiness
    // A gbuffer program that SAMPLES a colortex it does not write — gbuffers_terrain reading gaux4, i.e. colortex7,
    // as the atmosphere and fog colour — must not have that texture attached at the same time, or the driver hits a
    // rendering feedback loop and the read returns garbage. Here it returned the in-progress colortex1, which is
    // why distant terrain fog blended toward the ~50 clamp and blew the horizon white
    public void retainColorAttachments(java.util.Set<Integer> keepLogical) {
        bind();
        for (Map.Entry<Integer, Integer> entry : this.colorAttachments.entrySet()) {
            int logicalIndex = entry.getKey();
            Integer point = this.logicalAttachmentPoints.get(logicalIndex);
            if (point == null) {
                continue;
            }
            int texture = keepLogical.contains(logicalIndex) ? entry.getValue() : 0;
            LWJGL.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + point,
                    GL11.GL_TEXTURE_2D, texture, 0);
        }
    }

    // Depth-only rendering, for the shadow pass
    public void noDrawBuffers() {
        bind();
        LWJGL.glDrawBuffers(GL11.GL_NONE);
    }

    // Sets the draw-buffer mask from colour attachment indices
    // A negative entry writes GL_NONE for that slot rather than being skipped, which keeps the shader's slot
    // numbering DENSE when an optional target turns out to be unavailable — dropping the entry instead would
    // renumber every slot after it and send each gl_FragData write to the wrong attachment
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

    // Selects which logical attachment glReadPixels and blits read
    public void readBuffer(int colorIndex) {
        validateColorAttachmentIndex(colorIndex);
        validateAttachedColorAttachmentIndex(colorIndex);
        bind();
        LWJGL.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0 + colorIndex);
    }

    // Within the driver's attachment limit
    private void validateColorAttachmentIndex(int colorIndex) {
        if (colorIndex < 0) {
            throw new IllegalArgumentException("Color attachment index must be non-negative: " + colorIndex);
        }
        if (colorIndex >= this.maxColorAttachments) {
            throw new IllegalArgumentException("Color attachment index " + colorIndex
                    + " exceeds GPU limit of " + this.maxColorAttachments);
        }
    }

    // Within the limit and actually attached
    private void validateAttachedColorAttachmentIndex(int colorIndex) {
        if (!this.attachmentLogicalIndices.containsKey(colorIndex)) {
            throw new IllegalArgumentException("No color texture is attached to physical color attachment " + colorIndex);
        }
    }

    // Texture at a logical index
    public int getColorAttachment(int index) {
        Integer texture = this.colorAttachments.get(index);
        return texture == null ? 0 : texture;
    }

    // Whether addDepthAttachment was called
    public boolean hasDepthAttachment() {
        return this.hasDepthAttachment;
    }

    // glCheckFramebufferStatus
    public int getStatus() {
        bind();
        return LWJGL.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
    }

    // Status is GL_FRAMEBUFFER_COMPLETE
    public boolean isComplete() {
        return getStatus() == GL30.GL_FRAMEBUFFER_COMPLETE;
    }

    // Deletes the FBO; attached textures are owned elsewhere
    @Override
    protected void destroyInternal() {
        LWJGL.glDeleteFramebuffers(getGlId());
    }
}
