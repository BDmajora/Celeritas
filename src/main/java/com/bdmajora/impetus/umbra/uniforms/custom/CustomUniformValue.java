package com.bdmajora.impetus.umbra.uniforms.custom;

// A custom-uniform value: a small vector of 1 to 4 float components
// Scalars, booleans (as 0/1) and integers are all width-1; vec2/vec3/vec4 are widths 2/3/4. One representation for
// all of them, because the expression language has no type declarations to distinguish them by
// Arithmetic broadcasts a width-1 operand across a wider one, which is GLSL's own scalar-vector rule and close
// enough for the expressions packs actually write
// An original compact implementation rather than a port of the full expression library Iris bundles — it covers the
// value shapes real packs use and nothing more
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

    // Component-wise binary op with width-1 broadcasting; the result takes the wider of the two widths
    // Two operands of differing widths where NEITHER is 1 is a pack error, not something to guess at
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
