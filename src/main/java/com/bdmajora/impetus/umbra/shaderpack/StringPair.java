package com.bdmajora.impetus.umbra.shaderpack;

import java.util.Objects;

// A pair of strings, because Java has no tuple type. Ported from Iris
// Used for the macro name/value pairs handed to the preprocessor, where a Map would lose ordering and duplicate
// keys both matter
public class StringPair {
    private final String key;
    private final String value;

    // Both non-null: a null macro name or value would reach the preprocessor as the literal text "null" and
    // produce a shader that compiles but is silently wrong, so it is rejected here instead
    public StringPair(String key, String value) {
        this.key = Objects.requireNonNull(key);
        this.value = Objects.requireNonNull(value);
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }
}
