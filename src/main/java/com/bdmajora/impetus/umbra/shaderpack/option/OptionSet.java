package com.bdmajora.impetus.umbra.shaderpack.option;

import com.bdmajora.impetus.umbra.Umbra;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

// Every configurable option found across a shader pack, deduplicated and split into boolean and string maps by name
// Where Iris DROPS an option whose duplicate declarations disagree, this keeps the FIRST definition. Several packs
// repeat an option across include paths on purpose, and dropping it leaves the preprocessor gates that reference it
// undefined — which does not fail the compile, it silently takes the wrong branch
// Ported from Iris; guava maps replaced with unmodifiable HashMaps
public class OptionSet {
    private final Map<String, MergedBooleanOption> booleanOptions;
    private final Map<String, MergedStringOption> stringOptions;

    private OptionSet(Builder builder) {
        this.booleanOptions = Collections.unmodifiableMap(new HashMap<>(builder.booleanOptions));
        this.stringOptions = Collections.unmodifiableMap(new HashMap<>(builder.stringOptions));
    }

    // By name, merged across files
    public Map<String, MergedBooleanOption> getBooleanOptions() {
        return this.booleanOptions;
    }

    // By name, merged across files
    public Map<String, MergedStringOption> getStringOptions() {
        return this.stringOptions;
    }

    // Whether the name is a boolean rather than a valued option
    public boolean isBooleanOption(String name) {
        return booleanOptions.containsKey(name);
    }

    // Starts an empty set
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Map<String, MergedBooleanOption> booleanOptions;
        private final Map<String, MergedStringOption> stringOptions;

        public Builder() {
            this.booleanOptions = new HashMap<>();
            this.stringOptions = new HashMap<>();
        }

        // Merges another set in
        public void addAll(OptionSet other) {
            if (this.booleanOptions.isEmpty()) {
                this.booleanOptions.putAll(other.booleanOptions);
            } else {
                other.booleanOptions.values().forEach(this::addBooleanOption);
            }

            if (this.stringOptions.isEmpty()) {
                this.stringOptions.putAll(other.stringOptions);
            } else {
                other.stringOptions.values().forEach(this::addStringOption);
            }
        }

        // Records a boolean option found at a location
        public void addBooleanOption(OptionLocation location, BooleanOption option) {
            addBooleanOption(new MergedBooleanOption(location, option));
        }

        // Merges an already-merged option; conflicting definitions are logged
        public void addBooleanOption(MergedBooleanOption proposed) {
            BooleanOption option = proposed.getOption();
            MergedBooleanOption existing = booleanOptions.get(option.getName());

            MergedBooleanOption merged;

            if (existing != null) {
                merged = existing.merge(proposed);

                if (merged == null) {
                    Umbra.logger().warn("Keeping first definition of ambiguous boolean option " + option.getName());
                    return;
                }
            } else {
                merged = proposed;
            }

            booleanOptions.put(option.getName(), merged);
        }

        // Records a valued option found at a location
        public void addStringOption(OptionLocation location, StringOption option) {
            addStringOption(new MergedStringOption(location, option));
        }

        // Merges an already-merged option; conflicting definitions are logged
        public void addStringOption(MergedStringOption proposed) {
            StringOption option = proposed.getOption();
            MergedStringOption existing = stringOptions.get(option.getName());

            MergedStringOption merged;

            if (existing != null) {
                merged = existing.merge(proposed);

                if (merged == null) {
                    Umbra.logger().warn("Keeping first definition of ambiguous string option " + option.getName());
                    return;
                }
            } else {
                merged = proposed;
            }

            stringOptions.put(option.getName(), merged);
        }

        // Finalises
        public OptionSet build() {
            return new OptionSet(this);
        }
    }
}
