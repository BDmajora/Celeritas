package com.bdmajora.impetus.iris.uniforms.custom;

/**
 * A custom-uniform value: a small vector of 1–4 float components. Scalars, booleans (0/1), and integers are all
 * width-1 values; vec2/vec3/vec4 use widths 2/3/4. Arithmetic broadcasts a width-1 operand across a wider one,
 * matching GLSL scalar-vector semantics closely enough for the expressions shader packs use in custom uniforms.
 *
 * <p>This is an original, compact implementation for Impetus — it is intentionally not a port of the full
 * expression library upstream Iris bundles, but covers the value shapes real packs use.
 */
public final class CustomUniformValue {
    public final float[] components;
    public final int width;

    private CustomUniformValue(float[] components) {
        this.components = components;
        this.width = components.length;
    }

    public static CustomUniformValue scalar(float value) {
        return new CustomUniformValue(new float[] { value });
    }

    public static CustomUniformValue bool(boolean value) {
        return scalar(value ? 1.0f : 0.0f);
    }

    public static CustomUniformValue of(float... components) {
        if (components.length < 1 || components.length > 4) {
            throw new IllegalArgumentException("Vector width must be 1-4, was " + components.length);
        }
        return new CustomUniformValue(components);
    }

    public float x() {
        return this.components[0];
    }

    public boolean asBoolean() {
        return this.components[0] != 0.0f;
    }

    /** Component-wise binary op with width-1 broadcasting; result width is the wider of the two. */
    public static CustomUniformValue combine(CustomUniformValue a, CustomUniformValue b, java.util.function.DoubleBinaryOperator op) {
        int width = Math.max(a.width, b.width);
        float[] out = new float[width];
        for (int i = 0; i < width; i++) {
            float av = a.width == 1 ? a.components[0] : a.components[i];
            float bv = b.width == 1 ? b.components[0] : b.components[i];
            out[i] = (float) op.applyAsDouble(av, bv);
        }
        return new CustomUniformValue(out);
    }

    public CustomUniformValue map(java.util.function.DoubleUnaryOperator op) {
        float[] out = new float[this.width];
        for (int i = 0; i < this.width; i++) {
            out[i] = (float) op.applyAsDouble(this.components[i]);
        }
        return new CustomUniformValue(out);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder("vec").append(this.width).append('(');
        for (int i = 0; i < this.width; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(this.components[i]);
        }
        return sb.append(')').toString();
    }
}
