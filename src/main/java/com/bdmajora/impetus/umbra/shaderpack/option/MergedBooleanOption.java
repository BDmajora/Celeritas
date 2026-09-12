package com.bdmajora.impetus.umbra.shaderpack.option;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

// One boolean option, deduplicated across every file and line it was declared in
// A pack declares the same `#define OPTION` in several includes, and the config screen has to show ONE control for
// it, so identical declarations are merged and the set of locations is carried along — that set is what the writer
// needs later to patch every copy when the user changes the value
// Ported from Iris; guava ImmutableSet replaced with an unmodifiable LinkedHashSet, which also keeps declaration
// order stable so the screen does not reshuffle between loads
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

    // Merges another declaration of the same option, or returns null when the two cannot be reconciled
    // Null rather than an exception, because an ambiguous option is not a load failure: the caller drops it from
    // the config screen and the pack still runs with whatever each copy declared
    public MergedBooleanOption merge(MergedBooleanOption other) {
        // Disagreeing defaults make the option genuinely ambiguous — there is no single value the screen could
        // show, and picking one would silently change what half the pack's includes compile with
        if (this.option.getDefaultValue() != other.option.getDefaultValue()) {
            return null;
        }

        BooleanOption option;

        // Keep whichever declaration carried a comment: the comment is the human-readable label the config screen
        // displays, and only one of several identical declarations usually has one
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
