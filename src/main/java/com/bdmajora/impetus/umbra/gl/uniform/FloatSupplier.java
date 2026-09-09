package com.bdmajora.impetus.umbra.gl.uniform;

// A primitive float supplier
// Exists because java.util.function has no FloatSupplier, and Supplier<Float> would box on every uniform update —
// once per uniform per program bind, which is thousands of allocations a frame
@FunctionalInterface
public interface FloatSupplier {
    float getAsFloat();
}
