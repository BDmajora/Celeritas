package com.bdmajora.impetus.umbra.shaderpack.option;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A string option deduplicated across all the locations it appears in. Merging fails (returns {@code null}) if the
 * default values disagree. Ported from Umbra; guava {@code ImmutableSet} replaced with an unmodifiable
 * {@link LinkedHashSet}.
 */
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
