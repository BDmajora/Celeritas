package com.bdmajora.impetus.umbra.shaderpack.option;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

// The string-valued counterpart of MergedBooleanOption, deduplicated across declaration sites with the location set for the writer; merge returns null on disagreeing defaults. From Iris, ImmutableSet replaced with an ordered LinkedHashSet
public class MergedStringOption {
    private final StringOption option;
    private final Set<OptionLocation> locations;

    MergedStringOption(StringOption option, Set<OptionLocation> locations) {
        this.option = option;
        this.locations = locations;
    }

    public MergedStringOption(OptionLocation location, StringOption option) {
        this.option = option;
        Set<OptionLocation> set = new LinkedHashSet<>();
        set.add(location);
        this.locations = Collections.unmodifiableSet(set);
    }

    // Unions locations; returns null when the definitions conflict
    public MergedStringOption merge(MergedStringOption other) {
        if (!this.option.getDefaultValue().equals(other.option.getDefaultValue())) {
            return null;
        }

        StringOption option;

        if (this.option.getComment().isPresent()) {
            option = this.option;
        } else {
            option = other.option;
        }

        Set<OptionLocation> mergedLocations = new LinkedHashSet<>();
        mergedLocations.addAll(this.locations);
        mergedLocations.addAll(other.locations);

        return new MergedStringOption(option, Collections.unmodifiableSet(mergedLocations));
    }

    // The option
    public StringOption getOption() {
        return option;
    }

    // Every file and line that defines it
    public Set<OptionLocation> getLocations() {
        return locations;
    }
}
