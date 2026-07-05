package org.taumc.celeritas.iris.shaderpack.option;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A boolean option deduplicated across all the locations it appears in. Merging two declarations of the same name
 * fails (returns {@code null}) if their default values disagree — the option is then ambiguous. Ported from Iris;
 * guava {@code ImmutableSet} replaced with an unmodifiable {@link LinkedHashSet}.
 */
public class MergedBooleanOption {
    private final BooleanOption option;
    private final Set<OptionLocation> locations;

    MergedBooleanOption(BooleanOption option, Set<OptionLocation> locations) {
        this.option = option;
        this.locations = locations;
    }

    public MergedBooleanOption(OptionLocation location, BooleanOption option) {
        this.option = option;
        Set<OptionLocation> set = new LinkedHashSet<>();
        set.add(location);
        this.locations = Collections.unmodifiableSet(set);
    }

    public MergedBooleanOption merge(MergedBooleanOption other) {
        if (this.option.getDefaultValue() != other.option.getDefaultValue()) {
            return null;
        }

        BooleanOption option;

        if (this.option.getComment().isPresent()) {
            option = this.option;
        } else {
            option = other.option;
        }

        Set<OptionLocation> mergedLocations = new LinkedHashSet<>();
        mergedLocations.addAll(this.locations);
        mergedLocations.addAll(other.locations);

        return new MergedBooleanOption(option, Collections.unmodifiableSet(mergedLocations));
    }

    public BooleanOption getOption() {
        return option;
    }

    public Set<OptionLocation> getLocations() {
        return locations;
    }
}
