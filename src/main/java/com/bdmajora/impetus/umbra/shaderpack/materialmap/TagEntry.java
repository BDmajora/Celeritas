package com.bdmajora.impetus.umbra.shaderpack.materialmap;

import java.util.Map;

// A %tag-form entry from block.properties, e.g. `block.10001 = %minecraft:leaves`
// 1.12.2 has no block-tag system at all, so these are parsed only so a modern pack's block.properties does not
// fail outright — the material mapping then skips every one of them
// Kept rather than dropped at parse time because the id and predicates are what the skip message names
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
