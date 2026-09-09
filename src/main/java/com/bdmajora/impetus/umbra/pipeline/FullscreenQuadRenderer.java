package com.bdmajora.impetus.umbra.pipeline;

import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL15;
import com.bdmajora.impetus.lwjgl.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// Draws the full-screen quad every composite, deferred and final pass renders through
// One tiny VAO and VBO holding four vertices — [0,1] position plus texcoord, drawn as a triangle strip
// This replaces the immediate-mode Tessellator quad OptiFine used, which cannot work at all once a core-profile
// VAO is current
// The vertex layout matches the attribute slots FullscreenTransformer-generated programs are linked against:
// a_Position at POSITION_SLOT, a_TexCoord at TEXCOORD_SLOT
public class FullscreenQuadRenderer {
    public static final int POSITION_SLOT = 0;
    public static final int TEXCOORD_SLOT = 1;
    // Modern (#version 130+) packs address the quad through the fixed-function built-ins gl_Vertex and
    // gl_MultiTexCoord0 rather than named attributes
    // On the compatibility profile those alias generic attribute locations 0 and 8, so POSITION_SLOT already feeds
    // gl_Vertex for free; the texcoord is additionally mirrored into slot 8 so gl_MultiTexCoord0 is populated too
    // Harmless on the GLSL-120 path, which reads its texcoord from slot 1 and ignores this
    public static final int MULTITEXCOORD0_SLOT = 8;

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
                    // x, y, u, v — triangle strip covering the whole screen. Positions are in [0,1] (matching Umbra's
                    // FullScreenQuadRenderer), NOT NDC [-1,1]: packs address the quad as gl_Vertex and expect [0,1]
                    // (e.g. superDuperVanilla does `gl_Vertex.xy * 2.0 - 1.0`; ftransform packs get the same NDC via
                    // the fullscreen ortho). The [0,1]->[-1,1] mapping lives in pushFullscreenFixedFunctionMatrices
                    // (modern path) and the gl_ProjectionMatrix/ftransform defines in FullscreenTransformer (legacy).
                    0.0f, 0.0f, 0.0f, 0.0f,
                    1.0f, 0.0f, 1.0f, 0.0f,
                    0.0f, 1.0f, 0.0f, 1.0f,
                    1.0f, 1.0f, 1.0f, 1.0f
            });
            LWJGL.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        }

        LWJGL.glEnableVertexAttribArray(POSITION_SLOT);
        LWJGL.glVertexAttribPointer(POSITION_SLOT, 2, GL11.GL_FLOAT, false, STRIDE, 0L);
        LWJGL.glEnableVertexAttribArray(TEXCOORD_SLOT);
        LWJGL.glVertexAttribPointer(TEXCOORD_SLOT, 2, GL11.GL_FLOAT, false, STRIDE, 2L * Float.BYTES);
        // Mirror the texcoord into slot 8 for modern packs' gl_MultiTexCoord0 (see MULTITEXCOORD0_SLOT).
        LWJGL.glEnableVertexAttribArray(MULTITEXCOORD0_SLOT);
        LWJGL.glVertexAttribPointer(MULTITEXCOORD0_SLOT, 2, GL11.GL_FLOAT, false, STRIDE, 2L * Float.BYTES);

        LWJGL.glBindVertexArray(0);
        LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    // Draws the quad against whatever program, framebuffer and samplers are already bound — this sets none of them
    // Leaves VAO 0 bound on the way out, so vanilla's immediate-mode drawing afterwards is not fed our vertex arrays
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
