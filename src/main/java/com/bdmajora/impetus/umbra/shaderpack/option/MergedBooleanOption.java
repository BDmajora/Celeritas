package com.bdmajora.impetus.umbra.shaderpack.option;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

// One boolean option deduplicated across every file and line it was declared in, so the config screen shows ONE control and the writer can patch every copy; from Iris, with ImmutableSet replaced by an unmodifiable LinkedHashSet that keeps declaration order stable
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

    // Merges another declaration of the same option, or null when irreconcilable; null rather than an exception since an ambiguous option just drops from the config screen and the pack still runs
    public MergedBooleanOption merge(MergedBooleanOption other) {
        // Disagreeing defaults make the option genuinely ambiguous: no single value the screen could show, and picking one would change what half the includes compile with
        if (this.option.getDefaultValue() != other.option.getDefaultValue()) {
            return null;
        }

        BooleanOption option;

        // Keep whichever declaration carried a comment, the human-readable label the screen displays, since usually only one of several identical declarations has one
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

    // The option
    public BooleanOption getOption() {
        return option;
    }

    // Every file and line that defines it
    public Set<OptionLocation> getLocations() {
        return locations;
    }
}
