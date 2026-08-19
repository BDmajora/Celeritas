package com.bdmajora.impetus.iris.shaderpack.materialmap;

import java.util.Map;

/**
 * A {@code %tag}-form entry from {@code block.properties}. Port of Iris's {@code TagEntry}. 1.12.2 has no block-tag
 * system, so these parse for compatibility but the material mapping skips them with a debug log.
 */
public final class TagEntry implements Entry {
    private final NamespacedId id;
    private final Map<String, String> propertyPredicates;

    public TagEntry(NamespacedId id, Map<String, String> propertyPredicates) {
        this.id = id;
        this.propertyPredicates = propertyPredicates;
    }

    public NamespacedId getId() {
        return this.id;
    }

    public Map<String, String> getPropertyPredicates() {
        return this.propertyPredicates;
    }
}
