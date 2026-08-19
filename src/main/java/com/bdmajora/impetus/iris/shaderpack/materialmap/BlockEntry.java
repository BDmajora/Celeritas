package com.bdmajora.impetus.iris.shaderpack.materialmap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * One block match from a {@code block.<id>} line: a block identifier plus optional blockstate property predicates
 * (e.g. {@code double_plant:half=lower}). Port of Iris's {@code BlockEntry}; predicates act as a filter — properties
 * the entry does not name match any value.
 */
public final class BlockEntry implements Entry {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    private final NamespacedId id;
    private final Map<String, String> propertyPredicates;

    public BlockEntry(NamespacedId id, Map<String, String> propertyPredicates) {
        this.id = id;
        this.propertyPredicates = propertyPredicates;
    }

    /**
     * Parses one entry token. Must not be empty. Follows Iris's {@code BlockEntry.parse} exactly:
     * {@code name}, {@code namespace:name}, {@code name:key=value:...}, {@code namespace:name:key=value:...},
     * and the {@code %}-prefixed forms of all of the above produce a {@link TagEntry} instead.
     */
    public static Entry parse(String entry) {
        if (entry.isEmpty()) {
            throw new IllegalArgumentException("Called BlockEntry::parse with an empty string");
        }

        boolean isTag = entry.startsWith("%");
        if (isTag) {
            entry = entry.replace("%", "");
        }
        // Some OptiFine-era packs accidentally write mod IDs as "namespace::block". Vanilla/Iris block IDs only use a
        // single namespace separator; without this tolerance the empty segment is parsed as the block name and the real
        // name is misreported as a malformed blockstate predicate.
        entry = entry.replaceAll(":{2,}", ":");

        String[] splitStates = entry.split(":");

        // Trivial case: no states, no namespace.
        if (splitStates.length == 1) {
            NamespacedId id = new NamespacedId("minecraft", entry);
            return isTag ? new TagEntry(id, Collections.emptyMap()) : new BlockEntry(id, Collections.emptyMap());
        }

        // No states involved, just a namespace.
        if (splitStates.length == 2 && !splitStates[1].contains("=")) {
            NamespacedId id = new NamespacedId(splitStates[0], splitStates[1]);
            return isTag ? new TagEntry(id, Collections.emptyMap()) : new BlockEntry(id, Collections.emptyMap());
        }

        // One or more state predicates involved.
        int statesStart;
        NamespacedId id;
        if (splitStates[1].contains("=")) {
            // Form: "tall_grass:half=upper"
            statesStart = 1;
            id = new NamespacedId("minecraft", splitStates[0]);
        } else {
            // Form: "minecraft:tall_grass:half=upper"
            statesStart = 2;
            id = new NamespacedId(splitStates[0], splitStates[1]);
        }

        Map<String, String> map = new HashMap<>();
        for (int index = statesStart; index < splitStates.length; index++) {
            String[] propertyParts = splitStates[index].split("=");
            if (propertyParts.length != 2) {
                LOGGER.warn("[Iris] The block ID map entry \"{}\" could not be fully parsed:", entry);
                LOGGER.warn("[Iris] - Block state property filters must be of the form \"key=value\", but {} is not", splitStates[index]);
                continue;
            }
            map.put(propertyParts[0], propertyParts[1]);
        }

        return isTag ? new TagEntry(id, map) : new BlockEntry(id, map);
    }

    public NamespacedId getId() {
        return this.id;
    }

    public Map<String, String> getPropertyPredicates() {
        return this.propertyPredicates;
    }
}
