package com.bdmajora.impetus.umbra.gl.uniform;

// A primitive float supplier; java.util.function has none, and Supplier<Float> would box once per uniform per bind, thousands of allocations a frame
@FunctionalInterface
public interface FloatSupplier {
    float getAsFloat();
}
