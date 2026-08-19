package com.bdmajora.impetus.iris.shaderpack.materialmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates the flattened (1.13+) vanilla block names a modern pack writes in its {@code block.properties} into the
 * 1.12.2 registry names that mean the same thing.
 * <p>
 * Packs written for 1.16+ only — Photon is the reference case — wrap their whole ID map in
 * {@code #if MC_VERSION >= 11300} and leave the {@code #else} branch empty. Preprocessed honestly for 1.12.2 that
 * yields zero declared IDs, every block reaches the shader as {@code mc_Entity.x == 0}, and every material test in the
 * pack fails: water renders as an untagged translucent quad (no wave displacement, no water normals, no SSR, no water
 * fog), nothing waves, leaves get no subsurface scattering and no light source is emissive. {@link IdMap} therefore
 * falls back to the pack's 1.13+ branch and runs each entry through here.
 * <p>
 * Only <em>renames</em> live in the table. A name that survived the flattening unchanged ({@code glowstone},
 * {@code beacon}, {@code obsidian}, …) is returned as-is, and a name with no 1.12.2 counterpart ({@code sculk},
 * {@code froglight}, …) is returned unchanged too — {@code BlockMaterialMapping} already skips identifiers the
 * registry does not know, which is the correct outcome for both.
 * <p>
 * Blockstate predicates from the pack's entry are carried onto the translated name. That is what makes
 * {@code sunflower:half=lower} become {@code double_plant:half=lower}, and it is harmless where the flattening turned
 * a state into its own block ({@code redstone_lamp:lit=true} becomes {@code lit_redstone_lamp}, whose states simply do
 * not have a {@code lit} property, and predicates naming an absent property are ignored by design).
 */
public final class ModernBlockNames {
    /** Modern name → the 1.12.2 entry token(s) that cover it. Tokens may carry their own state predicates. */
    private static final Map<String, String[]> RENAMES = new HashMap<>();

    /** {@code light_gray} was {@code silver} in 1.12.2; every other dye colour kept its name. */
    private static final String LEGACY_LIGHT_GRAY = "silver";

    /** Colour-suffixed families that 1.12.2 modelled as one block with a {@code color} property. */
    private static final Map<String, String> COLORED_FAMILIES = new HashMap<>();

    /** Colour-suffixed families that stayed one block per colour, so only the colour word needs fixing. */
    private static final String[] COLORED_BLOCK_SUFFIXES = {"_shulker_box", "_glazed_terracotta"};

    /** The dye colours as they are spelled on 1.13+; {@code light_gray} is the only one 1.12.2 disagrees about. */
    private static final java.util.Set<String> COLORS = new java.util.HashSet<>(java.util.Arrays.asList(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
            "cyan", "purple", "blue", "brown", "green", "red", "black"));

    static {
        // Fluids. Iris/OptiFine packs match the still and flowing blocks alike.
        RENAMES.put("water", new String[]{"water", "flowing_water"});
        RENAMES.put("lava", new String[]{"lava", "flowing_lava"});

        // Small plants.
        RENAMES.put("grass", new String[]{"tallgrass:type=tall_grass"});
        RENAMES.put("short_grass", new String[]{"tallgrass:type=tall_grass"});
        RENAMES.put("fern", new String[]{"tallgrass:type=fern"});
        RENAMES.put("dead_bush", new String[]{"deadbush"});
        RENAMES.put("dandelion", new String[]{"yellow_flower"});
        for (String flower : new String[]{"poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip",
                "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy"}) {
            RENAMES.put(flower, new String[]{"red_flower"});
        }
        for (String sapling : new String[]{"oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling",
                "acacia_sapling", "dark_oak_sapling"}) {
            RENAMES.put(sapling, new String[]{"sapling"});
        }
        RENAMES.put("sugar_cane", new String[]{"reeds"});
        RENAMES.put("lily_pad", new String[]{"waterlily"});
        RENAMES.put("cobweb", new String[]{"web"});
        RENAMES.put("melon", new String[]{"melon_block"});

        // Tall plants. 1.12.2 has a single double_plant block; the pack's half=lower/upper predicate does the work.
        for (String tall : new String[]{"tall_grass", "large_fern", "sunflower", "lilac", "rose_bush", "peony"}) {
            RENAMES.put(tall, new String[]{"double_plant"});
        }

        // Leaves and logs: two blocks each in 1.12.2, split by wood type.
        for (String leaves : new String[]{"oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves"}) {
            RENAMES.put(leaves, new String[]{"leaves"});
        }
        for (String leaves : new String[]{"acacia_leaves", "dark_oak_leaves"}) {
            RENAMES.put(leaves, new String[]{"leaves2"});
        }
        for (String log : new String[]{"oak_log", "spruce_log", "birch_log", "jungle_log",
                "oak_wood", "spruce_wood", "birch_wood", "jungle_wood"}) {
            RENAMES.put(log, new String[]{"log"});
        }
        for (String log : new String[]{"acacia_log", "dark_oak_log", "acacia_wood", "dark_oak_wood"}) {
            RENAMES.put(log, new String[]{"log2"});
        }
        for (String planks : new String[]{"oak_planks", "spruce_planks", "birch_planks", "jungle_planks",
                "acacia_planks", "dark_oak_planks"}) {
            RENAMES.put(planks, new String[]{"planks"});
        }

        // Stone and ground materials the packs give hardcoded specular to.
        RENAMES.put("sand", new String[]{"sand:variant=sand"});
        RENAMES.put("red_sand", new String[]{"sand:variant=red_sand"});
        for (String sandstone : new String[]{"chiseled_sandstone", "cut_sandstone", "smooth_sandstone"}) {
            RENAMES.put(sandstone, new String[]{"sandstone"});
        }
        for (String sandstone : new String[]{"chiseled_red_sandstone", "cut_red_sandstone", "smooth_red_sandstone"}) {
            RENAMES.put(sandstone, new String[]{"red_sandstone"});
        }
        RENAMES.put("granite", new String[]{"stone:variant=granite"});
        RENAMES.put("polished_granite", new String[]{"stone:variant=smooth_granite"});
        RENAMES.put("diorite", new String[]{"stone:variant=diorite"});
        RENAMES.put("polished_diorite", new String[]{"stone:variant=smooth_diorite"});
        RENAMES.put("andesite", new String[]{"stone:variant=andesite"});
        RENAMES.put("polished_andesite", new String[]{"stone:variant=smooth_andesite"});
        RENAMES.put("grass_block", new String[]{"grass"});
        RENAMES.put("dirt_path", new String[]{"grass_path"});
        RENAMES.put("stone_bricks", new String[]{"stonebrick"});
        RENAMES.put("nether_bricks", new String[]{"nether_brick"});
        RENAMES.put("terracotta", new String[]{"hardened_clay"});
        RENAMES.put("snow", new String[]{"snow_layer"});
        RENAMES.put("snow_block", new String[]{"snow"});
        RENAMES.put("slime_block", new String[]{"slime"});

        // Light sources. The flattening turned several lit states into their own 1.12.2 block.
        RENAMES.put("wall_torch", new String[]{"torch"});
        RENAMES.put("redstone_wall_torch", new String[]{"redstone_torch"});
        RENAMES.put("jack_o_lantern", new String[]{"lit_pumpkin"});
        RENAMES.put("magma_block", new String[]{"magma"});
        RENAMES.put("spawner", new String[]{"mob_spawner"});
        RENAMES.put("nether_portal", new String[]{"portal"});
        RENAMES.put("powered_rail", new String[]{"golden_rail"});

        COLORED_FAMILIES.put("_stained_glass_pane", "stained_glass_pane");
        COLORED_FAMILIES.put("_stained_glass", "stained_glass");
        COLORED_FAMILIES.put("_concrete_powder", "concrete_powder");
        COLORED_FAMILIES.put("_concrete", "concrete");
        COLORED_FAMILIES.put("_terracotta", "stained_hardened_clay");
        COLORED_FAMILIES.put("_wool", "wool");
        COLORED_FAMILIES.put("_carpet", "carpet");
        COLORED_FAMILIES.put("_bed", "bed");
    }

    private ModernBlockNames() {
    }

    /** @return the 1.12.2 equivalents of {@code entry}, or the entry itself when its name needs no translation. */
    public static List<BlockEntry> translate(BlockEntry entry) {
        if (!"minecraft".equals(entry.getId().getNamespace())) {
            return Collections.singletonList(entry);
        }
        String[] legacy = legacyTokensFor(entry.getId().getName(), entry.getPropertyPredicates());
        if (legacy == null) {
            return Collections.singletonList(entry);
        }
        List<BlockEntry> translated = new ArrayList<>(legacy.length);
        for (String token : legacy) {
            translated.add(withInheritedPredicates(token, entry.getPropertyPredicates()));
        }
        return translated;
    }

    private static String[] legacyTokensFor(String name, Map<String, String> predicates) {
        // Lit states that became separate blocks. The pack names the modern block plus `lit=true`; an unlit entry has
        // to keep pointing at the unlit 1.12.2 block, so this cannot go in the flat rename table.
        if ("true".equals(predicates.get("lit"))) {
            if ("redstone_lamp".equals(name)) {
                return new String[]{"lit_redstone_lamp"};
            }
            if ("furnace".equals(name)) {
                return new String[]{"lit_furnace"};
            }
        }

        String[] renamed = RENAMES.get(name);
        if (renamed != null) {
            return renamed;
        }

        for (Map.Entry<String, String> family : COLORED_FAMILIES.entrySet()) {
            String color = colorPrefix(name, family.getKey());
            if (color != null) {
                return new String[]{family.getValue() + ":color=" + color};
            }
        }
        for (String suffix : COLORED_BLOCK_SUFFIXES) {
            String color = colorPrefix(name, suffix);
            if (color != null) {
                return new String[]{color + suffix};
            }
        }
        return null;
    }

    /**
     * @return the 1.12.2 dye-colour word of a {@code <color><suffix>} name, or null when {@code name} isn't one.
     * The prefix must be an actual dye colour, otherwise {@code white_glazed_terracotta} would read as the
     * "white_glazed" colour of the {@code _terracotta} family.
     */
    private static String colorPrefix(String name, String suffix) {
        if (!name.endsWith(suffix) || name.length() == suffix.length()) {
            return null;
        }
        String color = name.substring(0, name.length() - suffix.length());
        if (!COLORS.contains(color)) {
            return null;
        }
        return "light_gray".equals(color) ? LEGACY_LIGHT_GRAY : color;
    }

    private static BlockEntry withInheritedPredicates(String token, Map<String, String> inherited) {
        BlockEntry parsed = (BlockEntry) BlockEntry.parse(token);
        if (inherited.isEmpty()) {
            return parsed;
        }
        // The translated token is the more specific statement of the two, so it wins any key collision.
        Map<String, String> merged = new HashMap<>(inherited);
        merged.putAll(parsed.getPropertyPredicates());
        return new BlockEntry(parsed.getId(), merged);
    }
}
