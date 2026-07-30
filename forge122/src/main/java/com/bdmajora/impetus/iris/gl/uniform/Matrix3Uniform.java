package com.bdmajora.impetus.iris.gl.uniform;

import org.joml.Matrix3fc;
import com.bdmajora.impetus.lwjgl.MemoryStack;

import java.nio.FloatBuffer;
import java.util.function.Supplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

public class Matrix3Uniform extends Uniform {
    private final Supplier<Matrix3fc> value;

    public Matrix3Uniform(int location, Supplier<Matrix3fc> value) {
        super(location);
        this.value = value;
    }

    @Override
    public void update() {
        Matrix3fc matrix = this.value.get();
        if (matrix == null) {
            return;
        }
        try (MemoryStack stack = LWJGL.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(9);
            matrix.get(buffer);
            LWJGL.glUniformMatrix3fv(this.location, false, buffer);
        }
    }
}
