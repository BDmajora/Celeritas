package com.bdmajora.impetus.iris.shaderpack.materialmap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.iris.shaderpack.include.AbsolutePackPath;
import com.bdmajora.impetus.iris.shaderpack.preprocessor.PropertiesPreprocessor;

import java.util.ArrayList;
import java.util.Arrays;
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
    /**
     * {@code layer.<rendertype> = <block> ...} from block.properties — the pack reassigning which chunk render layer
     * a block meshes into (OptiFine shaders.txt "Block render layers"). Keyed by block id, value is the target
     * {@link net.minecraft.util.BlockRenderLayer}.
     */
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

        if (this.hasBlockProperties) {
            LOGGER.info("[Iris] block.properties: {} block ID(s) declared, {} render-layer override(s)",
                    this.blockPropertiesMap.size(), this.blockRenderLayerMap.size());
        }
    }

    /** {@code layer.<rendertype>} overrides: block id → the layer the pack wants it meshed into. */
    public Map<NamespacedId, net.minecraft.util.BlockRenderLayer> getBlockRenderLayerMap() {
        return this.blockRenderLayerMap;
    }

    /**
     * Parses {@code layer.solid|cutout|cutout_mipped|translucent = <block> ...}. OptiFine's own list is exactly these
     * four; anything else is a pack error. Tag entries ({@code %name}) are rejected the same way Iris rejects them —
     * a render layer has to resolve to concrete blocks.
     */
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
                LOGGER.warn("[Iris] block.properties: invalid block render type \"{}\", ignoring it", type);
                continue;
            }
            for (String part : line.substring(eq + 1).trim().split("\\s+")) {
                if (part.isEmpty()) {
                    continue;
                }
                if (part.startsWith("%")) {
                    LOGGER.warn("[Iris] block.properties: cannot use the tag \"{}\" in a render-layer override", part);
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

    /**
     * The version this pretends to be when a pack turns out to have no 1.12.2 mapping at all. 11300 is the first
     * flattened version, so it selects the oldest — and therefore closest — set of modern names.
     */
    private static final String FALLBACK_MC_VERSION = "11300";

    /**
     * Preprocesses {@code block.properties} honestly for 1.12.2, and only if that leaves the pack with no block IDs
     * whatsoever, re-reads it as a 1.13+ pack and translates the names back ({@link ModernBlockNames}).
     * <p>
     * Packs that never targeted 1.12 (Photon, and most post-1.16 packs) put their entire ID map behind
     * {@code #if MC_VERSION >= 11300} with an empty {@code #else}. Honest preprocessing then declares nothing, every
     * block arrives at the shader as {@code mc_Entity.x == 0}, and the pack's material tests all fail — water is not
     * recognised as water (flat, no waves, no reflections), nothing waves, nothing is emissive. A pack that does ship
     * a 1.12 branch is left strictly alone: this only runs when the honest result is empty.
     */
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
        LOGGER.info("[Iris] block.properties declares no 1.12.2 blocks; using its 1.13+ mapping instead "
                + "({} ID(s), names translated back to 1.12.2)", translated.size());
        return translated;
    }

    /** Parses {@code block.<id> = entry entry ...} lines from preprocessed properties text. */
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
                        LOGGER.debug("[Iris] Ignoring tag entry \"{}\" for block.{} (no tag system on 1.12.2)", part, intId);
                    }
                } catch (Exception e) {
                    LOGGER.warn("[Iris] Unexpected error while parsing a block.properties entry for block.{}: {}",
                            intId, e.getMessage());
                }
                }
            }
            entriesById.put(intId, Collections.unmodifiableList(entries));
        });

        return entriesById;
    }

    /**
     * Recovers two block IDs that a pack ran together by omitting the space between them.
     *
     * <p>Shader packs are hand-maintained text, and a dropped space produces a token like
     * {@code minecraft:gold_oreminecraft:redstone_ore}. BSL 10.1.3 ships exactly that on two lines of
     * its {@code block.properties}, which silently costs gold ore and redstone ore their material ID
     * — the ores stop being shaded as ores, with nothing in-game to explain why. OptiFine and Iris
     * upstream both drop the entry as unparseable.
     *
     * <p>The malformed shape is unambiguous, which is what makes fixing it safe rather than guesswork.
     * A colon-separated token is legal in exactly these forms:
     *
     * <ul>
     *   <li>{@code path}
     *   <li>{@code namespace:path}
     *   <li>{@code namespace:path:key=value...} — every segment past the second is a state filter and
     *       <em>must</em> contain {@code =}
     *   <li>{@code path:key=value}
     * </ul>
     *
     * <p>So three-or-more segments where two consecutive non-leading segments both lack {@code =}
     * cannot be a valid entry, and can only be two IDs with the separator missing. The split point is
     * the trailing namespace embedded in the joined segment.
     *
     * @return the token split into its constituent IDs, or the token unchanged if it is well-formed
     */
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

            LOGGER.warn("[Iris] block.{} entry \"{}\" is missing a space between IDs; reading it as {}",
                    intId, token, recovered);

            return recovered;
        }

        return Collections.singletonList(token);
    }

    /**
     * @return the namespace {@code segment} ends with, leaving a non-empty path in front of it, or
     *         {@code null} if it does not end with one we recognise.
     */
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
