package com.bdmajora.impetus.umbra.shaderpack.materialmap;

import java.util.Map;

// A %tag entry from block.properties (`block.10001 = %minecraft:leaves`); 1.12.2 has no block tags, so these parse only so a modern pack does not fail outright, and the mapping skips them with a message naming the id
public final class TagEntry implements Entry {
    private final NamespacedId id;
    private final Map<String, String> propertyPredicates;

    public TagEntry(NamespacedId id, Map<String, String> propertyPredicates) {
        this.id = id;
        this.propertyPredicates = propertyPredicates;
    }

    // The tag
    public NamespacedId getId() {
        return this.id;
    }

    // property=value conditions
    public Map<String, String> getPropertyPredicates() {
        return this.propertyPredicates;
    }
}
