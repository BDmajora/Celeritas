package com.bdmajora.impetus.umbra.shaderpack;

import java.util.function.BooleanSupplier;

// A tri-state boolean for pack directives (from Iris); an unwritten directive is DEFAULT, NOT `false`, since the fallback is often true (back-face culling) and collapsing them would silently change rendering
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

    // Resolves against a computed fallback evaluated only when unset; callers whose default depends on a GL query or pack scan need the laziness
    public boolean orElseGet(BooleanSupplier defaultValue) {
        if (this == DEFAULT) {
            return defaultValue.getAsBoolean();
        }

        return this == TRUE;
    }
}
