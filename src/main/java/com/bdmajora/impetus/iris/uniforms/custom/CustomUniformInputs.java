package com.bdmajora.impetus.iris.uniforms.custom;

import com.bdmajora.impetus.iris.gl.uniform.FloatSupplier;
import com.bdmajora.impetus.iris.gl.uniform.UniformCollector;
import com.bdmajora.impetus.iris.gl.uniform.UniformUpdateFrequency;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A {@link UniformCollector} that captures name→supplier pairs instead of uploading them, so custom uniform
 * expressions can read every built-in uniform value (rainStrength, eyeAltitude, sunPosition, frameTimeCounter, …)
 * through the exact same registrations the real programs use. Matrices are accepted but not resolvable — the
 * expression language works on 1-4 component vectors, which covers what packs actually reference.
 */
public final class CustomUniformInputs implements UniformCollector {
    private final Map<String, Supplier<CustomUniformValue>> inputs = new HashMap<>();

    public CustomUniformValue resolve(String name) {
        Supplier<CustomUniformValue> supplier = this.inputs.get(name);
        return supplier != null ? supplier.get() : null;
    }

    public boolean has(String name) {
        return this.inputs.containsKey(name);
    }

    @Override
    public UniformCollector uniform1f(UniformUpdateFrequency frequency, String uniformName, FloatSupplier value) {
        this.inputs.put(uniformName, () -> CustomUniformValue.scalar(value.getAsFloat()));
        return this;
    }

    @Override
    public UniformCollector uniform1i(UniformUpdateFrequency frequency, String uniformName, IntSupplier value) {
        this.inputs.put(uniformName, () -> CustomUniformValue.scalar(value.getAsInt()));
        return this;
    }

    @Override
    public UniformCollector uniform2f(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector2f> value) {
        this.inputs.put(uniformName, () -> {
            Vector2f v = value.get();
            return CustomUniformValue.of(v.x, v.y);
        });
        return this;
    }

    @Override
    public UniformCollector uniform2i(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector2i> value) {
        this.inputs.put(uniformName, () -> {
            Vector2i v = value.get();
            return CustomUniformValue.of(v.x, v.y);
        });
        return this;
    }

    @Override
    public UniformCollector uniform3f(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector3f> value) {
        this.inputs.put(uniformName, () -> {
            Vector3f v = value.get();
            return CustomUniformValue.of(v.x, v.y, v.z);
        });
        return this;
    }

    @Override
    public UniformCollector uniform3i(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector3i> value) {
        this.inputs.put(uniformName, () -> {
            Vector3i v = value.get();
            return CustomUniformValue.of(v.x, v.y, v.z);
        });
        return this;
    }

    @Override
    public UniformCollector uniform4f(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector4f> value) {
        this.inputs.put(uniformName, () -> {
            Vector4f v = value.get();
            return CustomUniformValue.of(v.x, v.y, v.z, v.w);
        });
        return this;
    }

    @Override
    public UniformCollector uniform4i(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector4i> value) {
        this.inputs.put(uniformName, () -> {
            Vector4i v = value.get();
            return CustomUniformValue.of(v.x, v.y, v.z, v.w);
        });
        return this;
    }

    @Override
    public UniformCollector uniformMatrix3(UniformUpdateFrequency frequency, String uniformName, Supplier<Matrix3fc> value) {
        for (int column = 0; column < 3; column++) {
            for (int row = 0; row < 3; row++) {
                final int index = column * 3 + row;
                this.inputs.put(uniformName + "." + column + "." + row, () -> {
                    Matrix3fc matrix = value.get();
                    if (matrix == null) {
                        return CustomUniformValue.scalar(0.0f);
                    }
                    return CustomUniformValue.scalar(matrix.get(new float[9])[index]);
                });
            }
        }
        return this;
    }

    @Override
    public UniformCollector uniformMatrix(UniformUpdateFrequency frequency, String uniformName, Supplier<Matrix4fc> value) {
        // A matrix is not representable as an expression value, but OptiFine/Iris custom uniforms can read single
        // cells with GLSL-style indexing, e.g. MakeUp's `1.0 / atan(1.0 / gbufferProjection.1.1)` (column.row).
        // Register one scalar input per cell under the exact dotted name the expression parser produces.
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                final int index = column * 4 + row;
                this.inputs.put(uniformName + "." + column + "." + row, () -> {
                    Matrix4fc matrix = value.get();
                    if (matrix == null) {
                        return CustomUniformValue.scalar(0.0f);
                    }
                    return CustomUniformValue.scalar(matrix.get(new float[16])[index]);
                });
            }
        }
        return this;
    }
}
