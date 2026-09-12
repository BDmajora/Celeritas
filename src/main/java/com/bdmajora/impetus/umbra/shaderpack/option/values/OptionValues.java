package com.bdmajora.impetus.umbra.shaderpack.option.values;

import com.bdmajora.impetus.umbra.Umbra;
import com.bdmajora.impetus.umbra.shaderpack.OptionalBoolean;
import com.bdmajora.impetus.umbra.shaderpack.option.OptionSet;

import java.util.Optional;

// Snapshot of every configurable option's current value; only values that differ from the pack default are stored
// Ported from Umbra; Umbra.logger replaced with Umbra.logger()
public interface OptionValues {
    OptionalBoolean getBooleanValue(String name);

    Optional<String> getStringValue(String name);

    // Changed value, else the declared default
    default boolean getBooleanValueOrDefault(String name) {
        return getBooleanValue(name).orElseGet(() -> {
            if (!getOptionSet().getBooleanOptions().containsKey(name)) {
                Umbra.logger().warn("Tried to get boolean value for unknown option: " + name + ", defaulting to true!");
                return true;
            }
            return getOptionSet().getBooleanOptions().get(name).getOption().getDefaultValue();
        });
    }

    // Changed value, else the declared default
    default String getStringValueOrDefault(String name) {
        return getStringValue(name).orElseGet(() -> getOptionSet().getStringOptions().get(name).getOption().getDefaultValue());
    }

    int getOptionsChanged();

    MutableOptionValues mutableCopy();

    ImmutableOptionValues toImmutable();

    OptionSet getOptionSet();
}
