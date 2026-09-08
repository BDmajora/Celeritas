package com.bdmajora.impetus.umbra.shaderpack.option.values;

import com.bdmajora.impetus.umbra.shaderpack.OptionalBoolean;
import com.bdmajora.impetus.umbra.shaderpack.option.OptionSet;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An immutable snapshot of option values. Ported from Umbra; guava {@code ImmutableMap} replaced with unmodifiable
 * {@link HashMap}s.
 */
public class ImmutableOptionValues implements OptionValues {
    private final OptionSet options;
    private final Map<String, Boolean> booleanValues;
    private final Map<String, String> stringValues;

    ImmutableOptionValues(OptionSet options, Map<String, Boolean> booleanValues, Map<String, String> stringValues) {
        this.options = options;
        this.booleanValues = Collections.unmodifiableMap(new HashMap<>(booleanValues));
        this.stringValues = Collections.unmodifiableMap(new HashMap<>(stringValues));
    }

    @Override
    public OptionalBoolean getBooleanValue(String name) {
        if (booleanValues.containsKey(name)) {
            return booleanValues.get(name) ? OptionalBoolean.TRUE : OptionalBoolean.FALSE;
        } else {
            return OptionalBoolean.DEFAULT;
        }
    }

    @Override
    public Optional<String> getStringValue(String name) {
        return Optional.ofNullable(stringValues.get(name));
    }

    @Override
    public int getOptionsChanged() {
        return this.stringValues.size() + this.booleanValues.size();
    }

    @Override
    public MutableOptionValues mutableCopy() {
        return new MutableOptionValues(options, new HashMap<>(booleanValues), new HashMap<>(stringValues));
    }

    @Override
    public ImmutableOptionValues toImmutable() {
        return this;
    }

    @Override
    public OptionSet getOptionSet() {
        return options;
    }
}
