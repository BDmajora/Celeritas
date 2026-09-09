package com.bdmajora.impetus.umbra.shaderpack.materialmap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.umbra.shaderpack.include.AbsolutePackPath;
import com.bdmajora.impetus.umbra.shaderpack.preprocessor.PropertiesPreprocessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// The pack's parsed ID-map properties: block.properties, item.properties, entity.properties
// Port of Iris's IdMap, working over the in-memory sources map rather than the filesystem
// Each file goes through PropertiesPreprocessor first, so version- and option-gated sections resolve exactly as
// they would under OptiFine or Iris rather than being read literally
// Free of Minecraft: entries here are name and predicate DESCRIPTIONS. BlockMaterialMapping resolves them against
// the 1.12.2 block registry later, when the pipeline is built
// When a pack ships no block.properties at all, hasBlockProperties() is false and the terrain mesher keeps
// emitting raw 1.12.2 block IDs — which is exactly what classic OptiFine packs like LIGHT and Chocapic are written
// against, so no legacy-defaults table is needed here the way Iris needs its LegacyIdMap
public final class IdMap {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");

    private static final AbsolutePackPath BLOCK_PROPERTIES = AbsolutePackPath.fromAbsolutePath("/block.properties");
    private static final AbsolutePackPath ITEM_PROPERTIES = AbsolutePackPath.fromAbsolutePath("/item.properties");
    private static final AbsolutePackPath ENTITY_PROPERTIES = AbsolutePackPath.fromAbsolutePath("/entity.properties");

    // block.<id> entries in DECLARATION ORDER, which is significant: first match wins, matching OptiFine
    private final Map<Integer, List<BlockEntry>> blockPropertiesMap;
    // item.<id> entries. Parsed so a pack declaring them loads cleanly; nothing consumes them yet
    private final Map<NamespacedId, Integer> itemIdMap;
    // entity.<id> entries, likewise parsed but not yet consumed
    private final Map<NamespacedId, Integer> entityIdMap;
    private final boolean hasBlockProperties;
    // layer.<rendertype> = <block> ... from block.properties: the pack reassigning which chunk render layer a block
    // meshes into, OptiFine's "Block render layers" feature
    // Keyed by block id, valued by the target BlockRenderLayer
    private final Map<NamespacedId, net.minecraft.util.BlockRenderLayer> blockRenderLayerMap;

    public IdMap(Map<AbsolutePackPath, String> sources, Map<String, String> preprocessorDefines) {
        String blockProperties = sources.get(BLOCK_PROPERTIES);
        this.hasBlockProperties = blockProperties != null;
        this.blockPropertiesMap = blockProperties != null
                ? parseBlockMapWithModernFallback(blockProperties, preprocessorDefines)
                : Collections.emptyMap();

        String itemProperties = sources.get(ITEM_PROPERTIES);
        this.itemIdMap = itemProperties != null
                ? parseIdMap(PropertiesPreprocessor.preprocess(itemProperties, preprocessorDefines), "item.")
                : Collections.emptyMap();

        String entityProperties = sources.get(ENTITY_PROPERTIES);
        this.entityIdMap = entityProperties != null
                ? parseIdMap(PropertiesPreprocessor.preprocess(entityProperties, preprocessorDefines), "entity.")
                : Collections.emptyMap();

        this.blockRenderLayerMap = blockProperties != null
                ? parseRenderLayerMap(PropertiesPreprocessor.preprocess(blockProperties, preprocessorDefines))
                : Collections.emptyMap();
    }

    // The layer overrides, consumed by the mesher when it decides which layer each block belongs to
    public Map<NamespacedId, net.minecraft.util.BlockRenderLayer> getBlockRenderLayerMap() {
        return this.blockRenderLayerMap;
    }

    // Parses layer.solid, layer.cutout, layer.cutout_mipped and layer.translucent — OptiFine's list is exactly
    // those four, so anything else is a pack error rather than an extension
    // Tag entries (%name) are rejected here the same way Iris rejects them: a render layer has to resolve to
    // concrete blocks, and a tag names a set that 1.12.2 cannot enumerate at all
    private static Map<NamespacedId, net.minecraft.util.BlockRenderLayer> parseRenderLayerMap(String preprocessed) {
        Map<NamespacedId, net.minecraft.util.BlockRenderLayer> overrides = new LinkedHashMap<>();
        for (String rawLine : preprocessed.split("\r\n|\r|\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.charAt(0) == '#' || !line.startsWith("layer.")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String type = line.substring("layer.".length(), eq).trim();
            net.minecraft.util.BlockRenderLayer layer = parseRenderLayer(type);
            if (layer == null) {
                LOGGER.warn("[Umbra] block.properties: invalid block render type \"{}\", ignoring it", type);
                continue;
            }
            for (String part : line.substring(eq + 1).trim().split("\\s+")) {
                if (part.isEmpty()) {
                    continue;
                }
                if (part.startsWith("%")) {
                    LOGGER.warn("[Umbra] block.properties: cannot use the tag \"{}\" in a render-layer override", part);
                    continue;
                }
                // Strip any block-state qualifier (`minecraft:glass:color=red`); the layer applies to the block.
                String id = part.split(":(?=[^:]*=)")[0];
                overrides.put(new NamespacedId(id), layer);
            }
        }
        return overrides;
    }

    private static net.minecraft.util.BlockRenderLayer parseRenderLayer(String name) {
        switch (name) {
            case "solid":
                return net.minecraft.util.BlockRenderLayer.SOLID;
            case "cutout":
                return net.minecraft.util.BlockRenderLayer.CUTOUT;
            case "cutout_mipped":
                return net.minecraft.util.BlockRenderLayer.CUTOUT_MIPPED;
            case "translucent":
                return net.minecraft.util.BlockRenderLayer.TRANSLUCENT;
            default:
                return null;
        }
    }

    // The MC_VERSION to claim when a pack turns out to have no 1.12.2 mapping at all
    // 11300 is the FIRST flattened version, which selects the oldest and therefore closest set of modern names —
    // claiming a later version would pull in renames that drifted further from 1.12.2
    private static final String FALLBACK_MC_VERSION = "11300";

    // Preprocesses block.properties honestly for 1.12.2 first, and only when that leaves the pack with NO block IDs
    // whatsoever does it re-read the file as a 1.13+ pack and translate the names back through ModernBlockNames
    // Packs that never targeted 1.12 — Photon and most post-1.16 packs — put their entire ID map behind
    // `#if MC_VERSION >= 11300` with an empty #else. Honest preprocessing declares nothing, every block reaches the
    // shader as mc_Entity.x == 0, and every material test in the pack fails: water is not recognised as water so it
    // is flat with no waves and no reflections, nothing waves, nothing is emissive
    // A pack that DOES ship a 1.12 branch is left strictly alone, because this only runs when the honest result is
    // empty
    private static Map<Integer, List<BlockEntry>> parseBlockMapWithModernFallback(
            String blockProperties, Map<String, String> preprocessorDefines) {
        Map<Integer, List<BlockEntry>> declared =
                parseBlockMap(PropertiesPreprocessor.preprocess(blockProperties, preprocessorDefines));
        if (!declared.isEmpty()) {
            return declared;
        }

        Map<String, String> modernDefines = new LinkedHashMap<>(preprocessorDefines);
        modernDefines.put("MC_VERSION", FALLBACK_MC_VERSION);
        Map<Integer, List<BlockEntry>> modern =
                parseBlockMap(PropertiesPreprocessor.preprocess(blockProperties, modernDefines));
        if (modern.isEmpty()) {
            return declared;
        }

        Map<Integer, List<BlockEntry>> translated = new LinkedHashMap<>();
        modern.forEach((intId, entries) -> {
            List<BlockEntry> legacy = new ArrayList<>(entries.size());
            for (BlockEntry entry : entries) {
                legacy.addAll(ModernBlockNames.translate(entry));
            }
            translated.put(intId, Collections.unmodifiableList(legacy));
        });
        return translated;
    }

    // Parses the `block.<id> = entry entry ...` lines out of already-preprocessed properties text
    private static Map<Integer, List<BlockEntry>> parseBlockMap(String preprocessed) {
        Map<Integer, List<BlockEntry>> entriesById = new LinkedHashMap<>();

        forEachProperty(preprocessed, "block.", (intId, value) -> {
            List<BlockEntry> entries = new ArrayList<>();
            for (String rawPart : value.split("\\s+")) {
                if (rawPart.isEmpty()) {
                    continue;
                }
                for (String part : separateRunTogetherIds(rawPart, intId)) {
                try {
                    Entry entry = BlockEntry.parse(part);
                    if (entry instanceof BlockEntry) {
                        entries.add((BlockEntry) entry);
                    } else {
                        // 1.12.2 has no block tags; packs only reference them behind MC_VERSION gates anyway.
                    }
                } catch (Exception e) {
                    LOGGER.warn("[Umbra] Unexpected error while parsing a block.properties entry for block.{}: {}",
                            intId, e.getMessage());
                }
                }
            }
            entriesById.put(intId, Collections.unmodifiableList(entries));
        });

        return entriesById;
    }

    // Recovers two block IDs a pack ran together by omitting the space between them
    // Shader packs are hand-maintained text, and a dropped space produces a token like
    // minecraft:gold_oreminecraft:redstone_ore. BSL 10.1.3 ships exactly that on two lines of its block.properties,
    // which silently costs gold ore and redstone ore their material ID — the ores stop being shaded as ores with
    // nothing in game to explain why. OptiFine and Iris both just drop the entry as unparseable
    //
    // Fixing it is safe rather than guesswork because the malformed shape is unambiguous. A colon-separated token
    // is legal in exactly four forms
    //   path
    //   namespace:path
    //   namespace:path:key=value... — every segment past the second is a state filter and MUST contain =
    //   path:key=value
    // So three or more segments where two consecutive non-leading segments both lack = cannot be a valid entry at
    // all, and can only be two IDs with the separator missing
    // The split point is the trailing namespace embedded in the joined segment
    // Returns the token split into its constituent IDs, or the token unchanged when it is well-formed
    private static List<String> separateRunTogetherIds(String token, int intId) {
        String[] parts = token.split(":");

        if (parts.length < 3) {
            return Collections.singletonList(token);
        }

        for (int i = 1; i <= parts.length - 2; i++) {
            if (parts[i].contains("=") || parts[i + 1].contains("=")) {
                continue;
            }

            // parts[i] is "<path><namespace>". The namespace of the token we are already inside is by
            // far the likeliest, because this is a typo in a list of same-namespace entries.
            String namespace = findTrailingNamespace(parts[i], parts[0]);

            if (namespace == null) {
                continue;
            }

            String path = parts[i].substring(0, parts[i].length() - namespace.length());
            String head = String.join(":", Arrays.copyOfRange(parts, 0, i)) + ":" + path;
            String tail = namespace + ":" + String.join(":", Arrays.copyOfRange(parts, i + 1, parts.length));

            List<String> recovered = new ArrayList<>();
            recovered.add(head);
            // Recurse: three or more IDs can be run together by the same mistake.
            recovered.addAll(separateRunTogetherIds(tail, intId));

            LOGGER.warn("[Umbra] block.{} entry \"{}\" is missing a space between IDs; reading it as {}",
                    intId, token, recovered);

            return recovered;
        }

        return Collections.singletonList(token);
    }

    // The namespace this segment ends with, provided a non-empty path remains in front of it, or null when it ends
    // with no namespace we recognise — the "non-empty path" condition is what stops a bare namespace from matching
    private static String findTrailingNamespace(String segment, String enclosingNamespace) {
        for (String candidate : new String[] {enclosingNamespace, "minecraft"}) {
            if (candidate == null || candidate.isEmpty() || candidate.length() >= segment.length()) {
                continue;
            }

            if (segment.endsWith(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    // Parses the plain `<prefix><id> = name name ...` shape used by item.properties and entity.properties, which
    // unlike block.properties carry no state predicates
    private static Map<NamespacedId, Integer> parseIdMap(String preprocessed, String prefix) {
        Map<NamespacedId, Integer> idMap = new LinkedHashMap<>();
        forEachProperty(preprocessed, prefix, (intId, value) -> {
            for (String part : value.split("\\s+")) {
                if (part.isEmpty()) {
                    continue;
                }
                if (part.contains("=")) {
                    LOGGER.warn("[Umbra] State properties are not supported in {}<id> entries: {}", prefix, part);
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

    // Walks the `<prefix><int> = value` lines in DECLARATION ORDER, which first-match-wins depends on
    // Parsed by hand, splitting on the first =, rather than through java.util.Properties: that class applies
    // backslash-escape semantics which would mangle Windows-style paths and escaped characters in pack values, and
    // the line continuations it would handle were already joined by the preprocessor
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
                LOGGER.warn("[Umbra] Failed to parse properties line: invalid key {}", key);
                continue;
            }
            consumer.accept(intId, value);
        }
    }

    // Whether the pack ships a block.properties at all — when it does not, raw 1.12.2 block IDs are the contract
    // and the mesher emits those instead of mapped ones
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
