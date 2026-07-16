package com.bdmajora.impetus.iris.shaderpack.materialmap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.iris.shaderpack.include.AbsolutePackPath;
import com.bdmajora.impetus.iris.shaderpack.preprocessor.PropertiesPreprocessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parsed ID-map properties of a pack ({@code block.properties}, {@code item.properties},
 * {@code entity.properties}) — the port of Iris's {@code IdMap}, operating on the in-memory sources map instead of
 * the filesystem. Each file is run through the {@link PropertiesPreprocessor} first, so version- and option-gated
 * sections ({@code #if MC_VERSION >= 11300} …) resolve exactly as they would under OptiFine/Iris.
 * <p>
 * Minecraft-free: entries are name/predicate descriptions. {@code iris.material.BlockMaterialMapping} resolves them
 * against the 1.12.2 block registry when the pipeline is built.
 * <p>
 * When the pack ships no {@code block.properties}, {@link #hasBlockProperties()} is {@code false} and the terrain
 * mesher keeps emitting raw 1.12.2 block IDs — the behavior classic OptiFine packs (LIGHT, Chocapic) are written
 * against, making a legacy-defaults table (Iris's {@code LegacyIdMap}) unnecessary here.
 */
public final class IdMap {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    private static final AbsolutePackPath BLOCK_PROPERTIES = AbsolutePackPath.fromAbsolutePath("/block.properties");
    private static final AbsolutePackPath ITEM_PROPERTIES = AbsolutePackPath.fromAbsolutePath("/item.properties");
    private static final AbsolutePackPath ENTITY_PROPERTIES = AbsolutePackPath.fromAbsolutePath("/entity.properties");

    /** {@code block.<id>} entries in declaration order (order is significant: first match wins, OptiFine parity). */
    private final Map<Integer, List<BlockEntry>> blockPropertiesMap;
    /** {@code item.<id>} entries. Parsed for completeness; not yet consumed by the pipeline. */
    private final Map<NamespacedId, Integer> itemIdMap;
    /** {@code entity.<id>} entries. Parsed for completeness; not yet consumed by the pipeline. */
    private final Map<NamespacedId, Integer> entityIdMap;
    private final boolean hasBlockProperties;

    public IdMap(Map<AbsolutePackPath, String> sources, Map<String, String> preprocessorDefines) {
        String blockProperties = sources.get(BLOCK_PROPERTIES);
        this.hasBlockProperties = blockProperties != null;
        this.blockPropertiesMap = blockProperties != null
                ? parseBlockMap(PropertiesPreprocessor.preprocess(blockProperties, preprocessorDefines))
                : Collections.emptyMap();

        String itemProperties = sources.get(ITEM_PROPERTIES);
        this.itemIdMap = itemProperties != null
                ? parseIdMap(PropertiesPreprocessor.preprocess(itemProperties, preprocessorDefines), "item.")
                : Collections.emptyMap();

        String entityProperties = sources.get(ENTITY_PROPERTIES);
        this.entityIdMap = entityProperties != null
                ? parseIdMap(PropertiesPreprocessor.preprocess(entityProperties, preprocessorDefines), "entity.")
                : Collections.emptyMap();

        if (this.hasBlockProperties) {
            LOGGER.info("[Iris] block.properties: {} block ID(s) declared", this.blockPropertiesMap.size());
        }
    }

    /** Parses {@code block.<id> = entry entry ...} lines from preprocessed properties text. */
    private static Map<Integer, List<BlockEntry>> parseBlockMap(String preprocessed) {
        Map<Integer, List<BlockEntry>> entriesById = new LinkedHashMap<>();

        forEachProperty(preprocessed, "block.", (intId, value) -> {
            List<BlockEntry> entries = new ArrayList<>();
            for (String part : value.split("\\s+")) {
                if (part.isEmpty()) {
                    continue;
                }
                try {
                    Entry entry = BlockEntry.parse(part);
                    if (entry instanceof BlockEntry) {
                        entries.add((BlockEntry) entry);
                    } else {
                        // 1.12.2 has no block tags; packs only reference them behind MC_VERSION gates anyway.
                        LOGGER.debug("[Iris] Ignoring tag entry \"{}\" for block.{} (no tag system on 1.12.2)", part, intId);
                    }
                } catch (Exception e) {
                    LOGGER.warn("[Iris] Unexpected error while parsing a block.properties entry for block.{}: {}",
                            intId, e.getMessage());
                }
            }
            entriesById.put(intId, Collections.unmodifiableList(entries));
        });

        return entriesById;
    }

    /** Parses a plain {@code <prefix><id> = name name ...} map (item.properties / entity.properties, Iris parity). */
    private static Map<NamespacedId, Integer> parseIdMap(String preprocessed, String prefix) {
        Map<NamespacedId, Integer> idMap = new LinkedHashMap<>();
        forEachProperty(preprocessed, prefix, (intId, value) -> {
            for (String part : value.split("\\s+")) {
                if (part.isEmpty()) {
                    continue;
                }
                if (part.contains("=")) {
                    LOGGER.warn("[Iris] State properties are not supported in {}<id> entries: {}", prefix, part);
                    continue;
                }
                idMap.put(new NamespacedId(part), intId);
            }
        });
        return Collections.unmodifiableMap(idMap);
    }

    private interface PropertyConsumer {
        void accept(int intId, String value);
    }

    /**
     * Iterates {@code <prefix><int> = value} lines of preprocessed properties text in declaration order. Manual
     * parsing (split on the first {@code =}) rather than {@link java.util.Properties} to avoid its backslash-escape
     * semantics; continuations were already joined by the preprocessor.
     */
    private static void forEachProperty(String preprocessed, String prefix, PropertyConsumer consumer) {
        for (String line : preprocessed.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith(prefix)) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            int intId;
            try {
                intId = Integer.parseInt(key.substring(prefix.length()));
            } catch (NumberFormatException e) {
                LOGGER.warn("[Iris] Failed to parse properties line: invalid key {}", key);
                continue;
            }
            consumer.accept(intId, value);
        }
    }

    /** Whether the pack ships a {@code block.properties} at all (if not, raw 1.12.2 block IDs are the contract). */
    public boolean hasBlockProperties() {
        return this.hasBlockProperties;
    }

    public Map<Integer, List<BlockEntry>> getBlockProperties() {
        return this.blockPropertiesMap;
    }

    public Map<NamespacedId, Integer> getItemIdMap() {
        return this.itemIdMap;
    }

    public Map<NamespacedId, Integer> getEntityIdMap() {
        return this.entityIdMap;
    }
}
