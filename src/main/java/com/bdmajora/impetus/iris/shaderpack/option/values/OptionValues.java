package com.bdmajora.impetus.iris.shaderpack.option.values;

import com.bdmajora.impetus.iris.Iris;
import com.bdmajora.impetus.iris.shaderpack.OptionalBoolean;
import com.bdmajora.impetus.iris.shaderpack.option.OptionSet;

import java.util.Optional;

/**
 * A snapshot of the current value of every configurable option (only values that differ from the pack default are
 * stored). Ported from Iris; {@code Iris.logger} replaced with {@link Iris#logger()}.
 */
public interface OptionValues {
    OptionalBoolean getBooleanValue(String name);

    Optional<String> getStringValue(String name);

    default boolean getBooleanValueOrDefault(String name) {
        return getBooleanValue(name).orElseGet(() -> {
            if (!getOptionSet().getBooleanOptions().containsKey(name)) {
                Iris.logger().warn("Tried to get boolean value for unknown option: " + name + ", defaulting to true!");
                return true;
            }
            return getOptionSet().getBooleanOptions().get(name).getOption().getDefaultValue();
        });
    }

    default String getStringValueOrDefault(String name) {
        return getStringValue(name).orElseGet(() -> getOptionSet().getStringOptions().get(name).getOption().getDefaultValue());
    }

    int getOptionsChanged();

    MutableOptionValues mutableCopy();

    ImmutableOptionValues toImmutable();

    OptionSet getOptionSet();
}
