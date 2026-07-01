package org.taumc.celeritas.iris.gl.uniform;

import org.joml.Matrix4fc;
import org.taumc.celeritas.lwjgl.MemoryStack;

import java.nio.FloatBuffer;
import java.util.function.Supplier;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * A {@code mat4} uniform (the gbuffer / shadow model-view and projection matrices and their inverses).
 * <p>
 * Matrices are re-uploaded every update rather than diffed — comparing 16 floats is rarely cheaper than the upload,
 * and these are PER_FRAME values anyway. The float buffer is allocated on the LWJGL abstraction's thread-local stack.
 */
public class MatrixUniform extends Uniform {
    private final Supplier<Matrix4fc> value;

    public MatrixUniform(int location, Supplier<Matrix4fc> value) {
        super(location);
        this.value = value;
    }

    @Override
    public void update() {
        Matrix4fc matrix = this.value.get();
        if (matrix == null) {
            return;
        }
        try (MemoryStack stack = LWJGL.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(16);
            matrix.get(buffer);
            LWJGL.glUniformMatrix4fv(this.location, false, buffer);
        }
    }
}
