package com.bdmajora.impetus.iris.uniforms.custom;

import com.bdmajora.impetus.iris.gl.uniform.FloatSupplier;
import com.bdmajora.impetus.iris.gl.uniform.UniformCollector;
import com.bdmajora.impetus.iris.gl.uniform.UniformUpdateFrequency;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;

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
    public UniformCollector uniformMatrix(UniformUpdateFrequency frequency, String uniformName, Supplier<Matrix4fc> value) {
        // Matrices are not representable in the expression value model; expressions that need positions use the
        // dedicated vec3 uniforms (cameraPosition, sunPosition, ...) instead.
        return this;
    }
}
