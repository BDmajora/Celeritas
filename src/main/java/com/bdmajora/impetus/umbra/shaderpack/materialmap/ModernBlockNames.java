package com.bdmajora.impetus.umbra.shaderpack.materialmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Translates the flattened 1.13+ vanilla block names a modern pack writes in its block.properties into the 1.12.2
// registry names that mean the same thing
//
// Why it is needed at all: packs written for 1.16+ only — Photon being the reference case — wrap their whole ID map
// in `#if MC_VERSION >= 11300` and leave the #else branch empty
// Preprocessed honestly for 1.12.2 that yields ZERO declared IDs, so every block reaches the shader as
// mc_Entity.x == 0 and every material test in the pack fails: water renders as an untagged translucent quad with
// no wave displacement, no water normals, no SSR and no water fog; nothing waves; leaves get no subsurface
// scattering; no light source is emissive
// So IdMap falls back to the pack's 1.13+ branch instead and runs every entry through here
//
// The table holds RENAMES only. A name the flattening left unchanged (glowstone, beacon, obsidian) comes back
// as-is, and so does a name with no 1.12.2 counterpart at all (sculk, froglight) — BlockMaterialMapping already
// skips identifiers the registry does not know, which is the right outcome for both
//
// Blockstate predicates from the pack's entry are carried onto the translated name, which is what turns
// sunflower:half=lower into double_plant:half=lower
// Harmless where the flattening turned a state into its own block: redstone_lamp:lit=true becomes
// lit_redstone_lamp, whose states have no `lit` property, and a predicate naming an absent property is ignored by
// design
public final class ModernBlockNames {
    // Modern name -> the 1.12.2 entry tokens covering it. An array because one flattened name can need several
    // 1.12.2 entries, and the tokens may carry state predicates of their own
    private static final Map<String, String[]> RENAMES = new HashMap<>();

    // light_gray was called silver in 1.12.2 — the only dye colour whose name changed
    private static final String LEGACY_LIGHT_GRAY = "silver";

    // Colour-suffixed families 1.12.2 modelled as ONE block carrying a `color` property — so white_wool becomes
    // wool:color=white rather than a block of its own
    private static final Map<String, String> COLORED_FAMILIES = new HashMap<>();

    // Colour-suffixed families that were already one block PER colour on 1.12.2, so only the colour word itself
    // needs fixing — and in practice only light_gray does
    private static final String[] COLORED_BLOCK_SUFFIXES = {"_shulker_box", "_glazed_terracotta"};

    // The dye colours as 1.13+ spells them; light_gray is the only one 1.12.2 disagrees about
    private static final java.util.Set<String> COLORS = new java.util.HashSet<>(java.util.Arrays.asList(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
            "cyan", "purple", "blue", "brown", "green", "red", "black"));

    static {
        // Fluids. Umbra/OptiFine packs match the still and flowing blocks alike.
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

    // The 1.12.2 equivalents of one entry, or a single-element list holding the entry itself when its name needs
    // no translation
    // A list because one modern name can map onto several 1.12.2 blocks
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

    // The 1.12.2 dye-colour word of a <color><suffix> name, or null when the name is not one
    // The prefix is checked against the actual colour set rather than just split off, because otherwise
    // white_glazed_terracotta would read as the "white_glazed" colour of a _terracotta family
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
