package org.taumc.celeritas.iris.pipeline;

import org.taumc.celeritas.lwjgl.GL11;
import org.taumc.celeritas.lwjgl.GL15;
import org.taumc.celeritas.lwjgl.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Draws the full-screen quad for the composite/deferred/final passes. Owns a tiny VAO+VBO holding four vertices
 * (NDC position + texcoord, drawn as a triangle strip) — the modern replacement for the immediate-mode
 * {@code Tessellator} quad that OptiFine/AUSM used, which cannot work when a core-profile VAO is current.
 * <p>
 * Vertex layout matches the attribute slots {@link FullscreenTransformer}-generated programs are linked with:
 * {@code a_Position} at {@link #POSITION_SLOT}, {@code a_TexCoord} at {@link #TEXCOORD_SLOT}.
 */
public class FullscreenQuadRenderer {
    public static final int POSITION_SLOT = 0;
    public static final int TEXCOORD_SLOT = 1;

    private static final int STRIDE = 4 * Float.BYTES;

    private final int vertexArray;
    private final int vertexBuffer;
    private boolean destroyed;

    public FullscreenQuadRenderer() {
        this.vertexArray = LWJGL.glGenVertexArrays();
        this.vertexBuffer = LWJGL.glGenBuffers();

        LWJGL.glBindVertexArray(this.vertexArray);
        LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vertexBuffer);

        try (MemoryStack stack = LWJGL.stackPush()) {
            ByteBuffer data = stack.malloc(4 * STRIDE);
            FloatBuffer floats = data.asFloatBuffer();
            floats.put(new float[]{
                    // x, y, u, v — triangle strip covering the whole screen
                    -1.0f, -1.0f, 0.0f, 0.0f,
                    1.0f, -1.0f, 1.0f, 0.0f,
                    -1.0f, 1.0f, 0.0f, 1.0f,
                    1.0f, 1.0f, 1.0f, 1.0f
            });
            LWJGL.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        }

        LWJGL.glEnableVertexAttribArray(POSITION_SLOT);
        LWJGL.glVertexAttribPointer(POSITION_SLOT, 2, GL11.GL_FLOAT, false, STRIDE, 0L);
        LWJGL.glEnableVertexAttribArray(TEXCOORD_SLOT);
        LWJGL.glVertexAttribPointer(TEXCOORD_SLOT, 2, GL11.GL_FLOAT, false, STRIDE, 2L * Float.BYTES);

        LWJGL.glBindVertexArray(0);
        LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /** Draws the quad with whatever program/framebuffer/samplers are currently bound. Leaves VAO 0 bound. */
    public void draw() {
        LWJGL.glBindVertexArray(this.vertexArray);
        LWJGL.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        LWJGL.glBindVertexArray(0);
    }

    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        LWJGL.glDeleteBuffers(this.vertexBuffer);
        LWJGL.glDeleteVertexArrays(this.vertexArray);
    }
}
