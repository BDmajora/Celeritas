package com.bdmajora.impetus.umbra.shaderpack.option;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

// The string-valued counterpart of MergedBooleanOption: one option deduplicated across every declaration site,
// carrying the set of locations so the writer can patch all of them
// Merge fails the same way, returning null when the defaults disagree and the option is therefore ambiguous
// Ported from Iris; guava ImmutableSet replaced with an unmodifiable LinkedHashSet to keep declaration order
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

    public StringOption getOption() {
        return option;
    }

    public Set<OptionLocation> getLocations() {
        return locations;
    }
}
