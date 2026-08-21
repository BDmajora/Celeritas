package com.bdmajora.equilibrium.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The option tree — every {@code mixin.*} rule Equilibrium recognises, its default, and what it does.
 *
 * <p>Lithium generates this from {@code @MixinConfigOption} annotations on {@code package-info} files
 * at build time, writing out a properties resource the config loader then reads. That machinery is a
 * Gradle plugin plus a build-time annotation processor, neither of which this project has, and both
 * of which exist to solve a problem we do not have: Lithium ships three loader-specific option sets
 * and needs them generated per platform.
 *
 * <p>So the tree is declared here instead, once, and three consumers read it: {@link EquilibriumConfig}
 * builds the rules from it, the config-file writer uses the descriptions as comments, and
 * {@code EquilibriumOptionPages} builds the GUI tab from it. The important property is preserved — an
 * option's name <em>is</em> its mixin package path, so a rule automatically governs every mixin
 * beneath it without anything having to be wired up by hand.
 *
 * <p>The tree is smaller than Lithium's, and deliberately so. Roughly a third of Lithium's options
 * patch code that 1.12.2 does not have (everything touching {@code VoxelShape}, the brain-based AI
 * rewrite, chunk tickets, game events) and another handful patch code 1.12.2 already gets right —
 * {@code ExtendedBlockStorage} has counted its randomly-ticking blocks since 1.8, and every
 * {@code Profiler} entry point already returns immediately when profiling is off. An option that
 * cannot remove work is worse than no option, because it invites a user to spend a launch bisecting
 * something that was never doing anything. {@code EQUILIBRIUM_ROADMAP.md} lists what was dropped and
 * why.
 */
public final class EquilibriumOptions {
    /** Immutable, in declaration order; the config file and the GUI both present them this way. */
    private static final Map<String, Entry> ENTRIES = build();

    private EquilibriumOptions() {
    }

    /** One rule. */
    public static final class Entry {
        private final String name;
        private final boolean enabledByDefault;
        private final String description;
        private final String nonVanilla;
        private final Map<String, Boolean> dependencies;

        Entry(String name, boolean enabledByDefault, String description, String nonVanilla,
              Map<String, Boolean> dependencies) {
            this.name = name;
            this.enabledByDefault = enabledByDefault;
            this.description = description;
            this.nonVanilla = nonVanilla;
            this.dependencies = dependencies;
        }

        public String name() {
            return this.name;
        }

        public boolean enabledByDefault() {
            return this.enabledByDefault;
        }

        public String description() {
            return this.description;
        }

        /** How behaviour differs from vanilla, or null when it does not. */
        public String nonVanillaBehaviour() {
            return this.nonVanilla;
        }

        public Map<String, Boolean> dependencies() {
            return this.dependencies;
        }

        /** The name with the {@code mixin.} prefix removed, which is what the lang keys are built from. */
        public String path() {
            return this.name.substring("mixin.".length());
        }

        /** The top-level category, used to group the GUI tab into sections. */
        public String category() {
            String path = this.path();
            int split = path.indexOf('.');
            return split == -1 ? path : path.substring(0, split);
        }

        /** How deep this rule sits, so the GUI can indent children under their parent. */
        public int depth() {
            int depth = 0;

            for (int i = 0; i < this.name.length(); i++) {
                if (this.name.charAt(i) == '.') {
                    depth++;
                }
            }

            return depth - 1;
        }
    }

    public static Map<String, Entry> entries() {
        return ENTRIES;
    }

    public static Entry get(String name) {
        return ENTRIES.get(name);
    }

    /** The categories in declaration order, which is the order the GUI tab lists its groups in. */
    public static List<String> categories() {
        List<String> categories = new ArrayList<>();

        for (Entry entry : ENTRIES.values()) {
            String category = entry.category();

            if (!categories.contains(category)) {
                categories.add(category);
            }
        }

        return categories;
    }

    public static List<Entry> inCategory(String category) {
        List<Entry> entries = new ArrayList<>();

        for (Entry entry : ENTRIES.values()) {
            if (entry.category().equals(category)) {
                entries.add(entry);
            }
        }

        return entries;
    }

    private static Map<String, Entry> build() {
        Builder builder = new Builder();

        // ---------------------------------------------------------------- ai
        builder.add("mixin.ai", true,
                "Mob AI optimizations");
        builder.add("mixin.ai.goal_selector", true,
                "The AI goal selector holds its task sets in fastutil linked open hash sets rather than "
                        + "java.util.LinkedHashSet. Both sets are walked every tick for every mob in "
                        + "range, and the vanilla ones put every task behind its own node.");

        // ------------------------------------------------------------- alloc
        builder.add("mixin.alloc", true,
                "Patches that reduce memory allocations");
        builder.add("mixin.alloc.entity_tracker", true,
                "Entity trackers store their players in a fastutil open hash set instead of a "
                        + "java.util.HashSet, which allocates a node per player per tracked entity.");
        builder.add("mixin.alloc.deep_passengers", true,
                "Collecting an entity's passengers stops allocating a hash map for the overwhelmingly "
                        + "common case of an entity carrying nobody, and uses an identity set when it "
                        + "does carry someone.");
        builder.add("mixin.alloc.enum_values", true,
                "Avoid Enum#values() array copies in frequently called code");
        builder.add("mixin.alloc.enum_values.piston_block", true,
                "Piston extension checks reuse the shared facing array instead of copying "
                        + "EnumFacing.values().");
        builder.add("mixin.alloc.enum_values.piston_handler", true,
                "The piston structure resolver reuses the shared facing array instead of copying "
                        + "EnumFacing.values() once per block it moves.");
        builder.add("mixin.alloc.enum_values.redstone_wire", true,
                "Redstone wire power propagation reuses the shared facing array instead of copying "
                        + "EnumFacing.values() once per wire per power level.");

        // ------------------------------------------------------------- block
        builder.add("mixin.block", true,
                "Optimizations related to blocks");
        builder.add("mixin.block.hopper", true,
                "Hoppers remember the inventory above them and the one they face, instead of searching "
                        + "the world for both on every transfer attempt. An idle hopper attempts a "
                        + "transfer every tick, and each attempt that finds nothing costs a full entity "
                        + "query over the block.")
                .requires("mixin.util.block_entity_retrieval", true)
                .requires("mixin.util.data_storage", true);
        builder.add("mixin.block.redstone_wire", true,
                "Redstone wire power calculation reads each neighbouring block once instead of twice, "
                        + "and resolves the block above the wire once per update rather than once per "
                        + "direction.");

        // ------------------------------------------------------------- chunk
        builder.add("mixin.chunk", true,
                "Various world chunk optimizations");
        builder.add("mixin.chunk.no_validation", true,
                "Block reads no longer ask the world what type it is on every access in order to find "
                        + "out whether it is the debug world. The answer is fixed when the world is "
                        + "constructed, so it is resolved once.");

        // ------------------------------------------------------- collections
        builder.add("mixin.collections", true,
                "Various collection optimizations");
        builder.add("mixin.collections.mob_spawning", true,
                "The set of chunks eligible for mob spawning is a fastutil open hash set rather than a "
                        + "java.util.HashSet. It is rebuilt every spawn cycle and probed once per "
                        + "candidate chunk per player.");

        // ------------------------------------------------------------ entity
        builder.add("mixin.entity", true,
                "Various entity optimizations");
        builder.add("mixin.entity.collisions", true,
                "Various entity collision optimizations");
        builder.add("mixin.entity.collisions.movement", true,
                "Gathering the blocks an entity could collide with resolves a chunk section once per "
                        + "column rather than once per block. The search walks a column at a time, so "
                        + "sixteen consecutive reads share one section.")
                .requires("mixin.util.chunk_access", true);
        builder.add("mixin.entity.fast_elytra_check", true,
                "Skip writing to the tracked-data table every tick to record that a living entity is "
                        + "still not elytra flying. The write was already a no-op; the lookup it needed "
                        + "was not.");
        builder.add("mixin.entity.fast_retrieval", true,
                "Entity queries resolve each chunk once instead of testing whether it is loaded and then "
                        + "fetching the chunk that test just found.")
                .requires("mixin.util.chunk_access", true);
        builder.add("mixin.entity.fast_hand_swing", true,
                "Skip the hand swing progress and animation maths when the hand is not swinging. The "
                        + "division vanilla performs there needs the swing duration, and working that out "
                        + "means checking the entity's haste and mining fatigue effects.");

        // -------------------------------------------------------------- math
        builder.add("mixin.math", true,
                "Various math optimizations");
        builder.add("mixin.math.fast_blockpos", true,
                "Directional block position offsets are computed inline, and each direction's offsets "
                        + "are stored rather than derived from its axis on every read.");
        builder.add("mixin.math.fast_util", true,
                "Direction opposites avoid a modulo, and picking a random direction stops cloning the "
                        + "values array twice per call.");
        builder.add("mixin.math.sine_lut", true,
                "Replaces the 256 KB sine table with a 64 KB one that fits in cache, using "
                        + "trigonometric identities to reconstruct the quadrants it drops. Results are "
                        + "bit-for-bit identical to vanilla, and verified to be at startup.");

        // -------------------------------------------------------------- util
        builder.add("mixin.util", true,
                "Various utilities for other mixins. These add no optimization on their own; turning "
                        + "one off disables everything that depends on it.");
        builder.add("mixin.util.block_entity_retrieval", true,
                "Allows looking up a tile entity that already exists without constructing and "
                        + "registering one as a side effect of asking.");
        builder.add("mixin.util.data_storage", true,
                "Stores Equilibrium's per-world scratch data. None of it is saved; it exists so the "
                        + "other optimizations have somewhere to keep their counters.");
        builder.add("mixin.util.chunk_access", true,
                "Access a world's loaded chunks directly, without going through the chunk provider's "
                        + "dispatch and its map lookup.");

        // ------------------------------------------------------------- world
        builder.add("mixin.world", true,
                "Various world related optimizations");
        builder.add("mixin.world.block_entity_ticking", true,
                "Various tile entity ticking optimizations");
        builder.add("mixin.world.block_entity_ticking.sleeping", true,
                "Allows tile entities to skip work they can prove will do nothing.");
        builder.add("mixin.world.block_entity_ticking.sleeping.brewing_stand", true,
                "A brewing stand with no ingredient stops asking the brewing registry whether it can "
                        + "brew. On Forge that question walks every recipe every mod has registered, and "
                        + "it is asked once per stand per tick.");
        builder.add("mixin.world.block_entity_ticking.sleeping.furnace", true,
                "A furnace that is unlit, has nothing part-cooked and is missing either its fuel or its "
                        + "input skips its tick entirely. Every branch it would have taken is a no-op.");
        builder.add("mixin.world.explosions", true,
                "Various improvements to explosions.");
        builder.add("mixin.world.explosions.block_raycast", true,
                "Explosion block damage walks its 1352 rays through a chunk-section cursor and reads "
                        + "each position at most once, instead of a fresh world lookup and a fresh block "
                        + "position for every step of every ray.");
        builder.add("mixin.world.explosions.entity_raycast", true,
                "Explosion entity exposure caches the blocks its sample rays cross. Every ray of the "
                        + "same explosion crosses mostly the same blocks, and vanilla re-traces all of "
                        + "them for every entity.");
        builder.add("mixin.world.inline_block_access", true,
                "Block and block-state reads resolve the chunk through a small direct cache rather than "
                        + "the chunk provider's map lookup on every access.")
                .requires("mixin.util.chunk_access", true);
        builder.add("mixin.world.inline_height", true,
                "World height queries resolve the chunk once instead of testing whether it is loaded and "
                        + "then fetching the chunk that test just found.")
                .requires("mixin.util.chunk_access", true);
        builder.add("mixin.world.raycast", true,
                "Block ray tracing steps through the world without allocating a vector and a block "
                        + "position per step.");
        builder.add("mixin.world.tick_scheduler", true,
                "The set of pending scheduled block ticks is a fastutil open hash set rather than a "
                        + "java.util.HashSet, which removes a node allocation per scheduled tick and a "
                        + "pointer chase from every lookup. Redstone, liquids and crops schedule "
                        + "thousands of these per second on a busy world.");

        return builder.finish();
    }

    /** Small fluent helper so the tree above reads as a list rather than a wall of constructor calls. */
    private static final class Builder {
        private final Map<String, Entry> entries = new LinkedHashMap<>();
        private final Map<String, Map<String, Boolean>> dependencies = new LinkedHashMap<>();
        private String current;

        Builder add(String name, boolean enabledByDefault, String description) {
            return this.add(name, enabledByDefault, description, null);
        }

        Builder add(String name, boolean enabledByDefault, String description, String nonVanilla) {
            Map<String, Boolean> deps = new LinkedHashMap<>();

            if (this.entries.put(name, new Entry(name, enabledByDefault, description, nonVanilla,
                    Collections.unmodifiableMap(deps))) != null) {
                throw new IllegalStateException("Duplicate option: " + name);
            }

            this.dependencies.put(name, deps);
            this.current = name;
            return this;
        }

        Builder requires(String dependency, boolean requiredValue) {
            this.dependencies.get(this.current).put(dependency, requiredValue);
            return this;
        }

        Map<String, Entry> finish() {
            // Every dependency must name a real option. A typo here would silently disable nothing,
            // which is exactly the kind of bug that only ever shows up as "why is this mixin still
            // applied", so it is worth failing at class-init over.
            for (Map.Entry<String, Map<String, Boolean>> entry : this.dependencies.entrySet()) {
                for (String dependency : entry.getValue().keySet()) {
                    if (!this.entries.containsKey(dependency)) {
                        throw new IllegalStateException(
                                "Option '" + entry.getKey() + "' depends on unknown option '" + dependency + "'");
                    }
                }
            }

            return Collections.unmodifiableMap(this.entries);
        }
    }
}
