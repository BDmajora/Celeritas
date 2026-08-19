package com.bdmajora.impetus.iris.gl.uniform;

/** A primitive {@code float} supplier (avoids boxing through {@link java.util.function.Supplier}{@code <Float>}). */
@FunctionalInterface
public interface FloatSupplier {
    float getAsFloat();
}
