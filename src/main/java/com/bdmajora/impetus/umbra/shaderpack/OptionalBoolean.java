package com.bdmajora.impetus.umbra.shaderpack;

import java.util.function.BooleanSupplier;

// A tri-state boolean for the pack directive system, ported from Iris
// A directive the pack never wrote is DEFAULT, which is NOT the same as writing `false`: the fallback for an unset
// directive is often true (back-face culling, for instance), so collapsing the two would silently change rendering
public enum OptionalBoolean {
    DEFAULT,
    FALSE,
    TRUE;

    // Resolves against a constant fallback
    public boolean orElse(boolean defaultValue) {
        if (this == DEFAULT) {
            return defaultValue;
        }

        return this == TRUE;
    }

    // Resolves against a computed fallback, evaluated only when the directive is actually unset — the callers
    // whose default depends on a GL query or a pack scan need that laziness
    public boolean orElseGet(BooleanSupplier defaultValue) {
        if (this == DEFAULT) {
            return defaultValue.getAsBoolean();
        }

        return this == TRUE;
    }
}
