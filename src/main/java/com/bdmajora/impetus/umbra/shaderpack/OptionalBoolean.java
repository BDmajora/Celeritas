package com.bdmajora.impetus.umbra.shaderpack;

import java.util.function.BooleanSupplier;

/**
 * A tri-state boolean used throughout the option system: a value may be explicitly {@code TRUE}/{@code FALSE} or
 * unset ({@code DEFAULT}), in which case a fallback is consulted. Ported verbatim from Umbra.
 */
public enum OptionalBoolean {
    DEFAULT,
    FALSE,
    TRUE;

    public boolean orElse(boolean defaultValue) {
        if (this == DEFAULT) {
            return defaultValue;
        }

        return this == TRUE;
    }

    public boolean orElseGet(BooleanSupplier defaultValue) {
        if (this == DEFAULT) {
            return defaultValue.getAsBoolean();
        }

        return this == TRUE;
    }
}
