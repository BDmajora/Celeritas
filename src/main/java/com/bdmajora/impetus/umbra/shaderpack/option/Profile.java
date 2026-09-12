package com.bdmajora.impetus.umbra.shaderpack.option;

import com.bdmajora.impetus.umbra.shaderpack.option.values.OptionValues;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// A named preset from shaders.properties: a bundle of option values plus the programs the preset switches off
// Packs ship these as the Low/Medium/High/Ultra presets the config screen offers as one control
// The disabled-program list matters as much as the values: a preset turns effects off by dropping whole passes,
// not just by setting their options to zero
// Ported from Iris; guava collections replaced with unmodifiable Java ones
public final class Profile {
    public final String name;
    public final int precedence; // Used for prioritizing during matching
    public final Map<String, String> optionValues;
    public final List<String> disabledPrograms;

    private Profile(String name, Map<String, String> optionValues, List<String> disabledPrograms) {
        this.name = name;
        this.optionValues = optionValues;
        this.precedence = optionValues.size();
        this.disabledPrograms = disabledPrograms;
    }

    // Whether every option the profile sets holds its value
    public boolean matches(OptionSet options, OptionValues values) {
        for (Map.Entry<String, String> entry : this.optionValues.entrySet()) {
            String option = entry.getKey();
            String value = entry.getValue();

            if (options.getBooleanOptions().containsKey(option)) {
                boolean currentValue = values.getBooleanValueOrDefault(option);

                if (!Boolean.toString(currentValue).equals(value)) {
                    return false;
                }
            }
            if (options.getStringOptions().containsKey(option)) {
                String currentValue = values.getStringValueOrDefault(option);

                if (!value.equals(currentValue)) {
                    return false;
                }
            }
        }

        return true;
    }

    public static class Builder {
        private final String name;
        private final Map<String, String> optionValues = new HashMap<>();
        private final List<String> disabledPrograms = new ArrayList<>();

        public Builder(String name) {
            this.name = name;
        }

        // Sets one option
        public Builder option(String optionId, String value) {
            this.optionValues.put(optionId, value);

            return this;
        }

        // Turns one program off
        public Builder disableProgram(String programId) {
            this.disabledPrograms.add(programId);

            return this;
        }

        // Inherits a parent profile
        public Builder addAll(Profile other) {
            this.optionValues.putAll(other.optionValues);
            this.disabledPrograms.addAll(other.disabledPrograms);

            return this;
        }

        // Finalises
        public Profile build() {
            return new Profile(name,
                    Collections.unmodifiableMap(new HashMap<>(optionValues)),
                    Collections.unmodifiableList(new ArrayList<>(disabledPrograms)));
        }
    }
}
