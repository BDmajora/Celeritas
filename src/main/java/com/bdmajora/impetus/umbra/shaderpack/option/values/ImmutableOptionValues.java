package com.bdmajora.impetus.umbra.shaderpack.option.values;

import com.bdmajora.impetus.umbra.shaderpack.OptionalBoolean;
import com.bdmajora.impetus.umbra.shaderpack.option.OptionSet;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

// A frozen snapshot of the pack's option values once the user's choices are resolved, immutable since the compile path must see one consistent set for every program. From Iris with unmodifiable HashMaps
public class ImmutableOptionValues implements OptionValues {
    private final OptionSet options;
    private final Map<String, Boolean> booleanValues;
    private final Map<String, String> stringValues;

    ImmutableOptionValues(OptionSet options, Map<String, Boolean> booleanValues, Map<String, String> stringValues) {
        this.options = options;
        this.booleanValues = Collections.unmodifiableMap(new HashMap<>(booleanValues));
        this.stringValues = Collections.unmodifiableMap(new HashMap<>(stringValues));
    }

    // Changed value, or empty when at default
    @Override
    public OptionalBoolean getBooleanValue(String name) {
        if (booleanValues.containsKey(name)) {
            return booleanValues.get(name) ? OptionalBoolean.TRUE : OptionalBoolean.FALSE;
        } else {
            return OptionalBoolean.DEFAULT;
        }
    }

    // Changed value, or empty when at default
    @Override
    public Optional<String> getStringValue(String name) {
        return Optional.ofNullable(stringValues.get(name));
    }

    // How many differ from default
    @Override
    public int getOptionsChanged() {
        return this.stringValues.size() + this.booleanValues.size();
    }

    // Editable copy
    @Override
    public MutableOptionValues mutableCopy() {
        return new MutableOptionValues(options, new HashMap<>(booleanValues), new HashMap<>(stringValues));
    }

    // Already immutable; returns this
    @Override
    public ImmutableOptionValues toImmutable() {
        return this;
    }

    // The set these values belong to
    @Override
    public OptionSet getOptionSet() {
        return options;
    }
}
