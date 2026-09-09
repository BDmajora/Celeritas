package com.bdmajora.impetus.umbra.gl.uniform;

import org.joml.Matrix4fc;
import com.bdmajora.impetus.lwjgl.MemoryStack;

import java.nio.FloatBuffer;
import java.util.function.Supplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// A mat4 uniform: the gbuffer and shadow model-view and projection matrices, plus their inverses
// Unlike the scalar uniforms, this does NOT cache and diff. Comparing 16 floats is rarely cheaper than the upload
// itself, and every matrix here is a PER_FRAME value that changes almost every time anyway
public class MatrixUniform extends Uniform {
    private final Supplier<Matrix4fc> value;

    public MatrixUniform(int location, Supplier<Matrix4fc> value) {
        super(location);
        this.value = value;
    }

    @Override
    public void update() {
        Matrix4fc matrix = this.value.get();
        // A supplier can legitimately have nothing yet — an inverse of a matrix that has not been captured this
        // frame — and leaving the uniform at its previous value beats uploading garbage
        if (matrix == null) {
            return;
        }
        // Thread-local stack rather than a field or a fresh allocation: the buffer lives only for this call, and
        // the try-with-resources pops it even if the upload throws
        try (MemoryStack stack = LWJGL.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(16);
            matrix.get(buffer);
            // false = do not transpose; JOML already stores column-major, which is what GL expects
            LWJGL.glUniformMatrix4fv(this.location, false, buffer);
        }
    }
}
