package org.taumc.celeritas.iris.shaderpack.option;

import org.taumc.celeritas.iris.Iris;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The complete, deduplicated set of configurable options discovered across a shader pack, split into boolean and
 * string options keyed by name. Ambiguous options (same name, conflicting defaults) are dropped. Ported from Iris;
 * guava maps replaced with unmodifiable {@link HashMap}s and {@code Iris.logger} with {@link Iris#logger()}.
 */
public class OptionSet {
    private final Map<String, MergedBooleanOption> booleanOptions;
    private final Map<String, MergedStringOption> stringOptions;

    private OptionSet(Builder builder) {
        this.booleanOptions = Collections.unmodifiableMap(new HashMap<>(builder.booleanOptions));
        this.stringOptions = Collections.unmodifiableMap(new HashMap<>(builder.stringOptions));
    }

    public Map<String, MergedBooleanOption> getBooleanOptions() {
        return this.booleanOptions;
    }

    public Map<String, MergedStringOption> getStringOptions() {
        return this.stringOptions;
    }

    public boolean isBooleanOption(String name) {
        return booleanOptions.containsKey(name);
    }

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

        public void addBooleanOption(OptionLocation location, BooleanOption option) {
            addBooleanOption(new MergedBooleanOption(location, option));
        }

        public void addBooleanOption(MergedBooleanOption proposed) {
            BooleanOption option = proposed.getOption();
            MergedBooleanOption existing = booleanOptions.get(option.getName());

            MergedBooleanOption merged;

            if (existing != null) {
                merged = existing.merge(proposed);

                if (merged == null) {
                    Iris.logger().warn("Ignoring ambiguous boolean option " + option.getName());
                    booleanOptions.remove(option.getName());
                    return;
                }
            } else {
                merged = proposed;
            }

            booleanOptions.put(option.getName(), merged);
        }

        public void addStringOption(OptionLocation location, StringOption option) {
            addStringOption(new MergedStringOption(location, option));
        }

        public void addStringOption(MergedStringOption proposed) {
            StringOption option = proposed.getOption();
            MergedStringOption existing = stringOptions.get(option.getName());

            MergedStringOption merged;

            if (existing != null) {
                merged = existing.merge(proposed);

                if (merged == null) {
                    Iris.logger().warn("Ignoring ambiguous string option " + option.getName());
                    stringOptions.remove(option.getName());
                    return;
                }
            } else {
                merged = proposed;
            }

            stringOptions.put(option.getName(), merged);
        }

        public OptionSet build() {
            return new OptionSet(this);
        }
    }
}
