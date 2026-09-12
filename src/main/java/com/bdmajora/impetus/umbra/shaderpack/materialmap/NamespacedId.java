package com.bdmajora.impetus.umbra.shaderpack.materialmap;

import java.util.Objects;

// A namespace:name pair, defaulting the namespace to `minecraft` when the pack wrote a bare name
// Verbatim port of Iris's NamespacedId
// Deliberately performs no validation: a pack's block.properties routinely names blocks from mods that are not
// installed, and rejecting those here would fail the whole pack rather than skipping one line. Whatever resolves
// these against the registry owns deciding what exists
public final class NamespacedId {
    private final String namespace;
    private final String name;

    // Splits on the FIRST colon only, so a name containing further colons keeps them
    public NamespacedId(String combined) {
        int colonIdx = combined.indexOf(':');
        if (colonIdx == -1) {
            this.namespace = "minecraft";
            this.name = combined;
        } else {
            this.namespace = combined.substring(0, colonIdx);
            this.name = combined.substring(colonIdx + 1);
        }
    }

    public NamespacedId(String namespace, String name) {
        this.namespace = Objects.requireNonNull(namespace);
        this.name = Objects.requireNonNull(name);
    }

    // Before the colon, minecraft when absent
    public String getNamespace() {
        return this.namespace;
    }

    // After the colon
    public String getName() {
        return this.name;
    }

    // Both equals and hashCode are needed because these are map keys throughout the material mapping
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        NamespacedId that = (NamespacedId) o;
        return this.namespace.equals(that.namespace) && this.name.equals(that.name);
    }

    // Consistent with equals
    @Override
    public int hashCode() {
        return 31 * (31 + this.namespace.hashCode()) + this.name.hashCode();
    }

    // namespace:name
    @Override
    public String toString() {
        return this.namespace + ":" + this.name;
    }
}
