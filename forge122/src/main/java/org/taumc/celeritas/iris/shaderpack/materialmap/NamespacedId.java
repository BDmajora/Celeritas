package org.taumc.celeritas.iris.shaderpack.materialmap;

import java.util.Objects;

/**
 * A {@code namespace:name} pair with the {@code minecraft} default — verbatim port of Iris's {@code NamespacedId}.
 * Performs no validation; whatever converts these to registry lookups is responsible for that.
 */
public final class NamespacedId {
    private final String namespace;
    private final String name;

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

    public String getNamespace() {
        return this.namespace;
    }

    public String getName() {
        return this.name;
    }

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

    @Override
    public int hashCode() {
        return 31 * (31 + this.namespace.hashCode()) + this.name.hashCode();
    }

    @Override
    public String toString() {
        return this.namespace + ":" + this.name;
    }
}
