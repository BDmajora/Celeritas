package com.bdmajora.equilibrium.config;

import com.bdmajora.equilibrium.Equilibrium;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Options that other mods take out of our hands, and how to tell those mods are installed.
 *
 * <p>Lithium lets a mod ship a {@code lithium:options} block in its metadata and reads it out of the
 * loader's mod list. Nothing equivalent is available here: this runs during coremod setup, where FML
 * has not built a mod list and Forge's own classes are not safe to touch. What <em>is</em> available
 * is the class loader, and every mod that conflicts with something here is a coremod — it has to be,
 * because the things it does to the game are the same things we do.
 *
 * <p>So detection is by class presence, and the table below is the whole of it. Each entry names a
 * mod, a class that only exists when that mod does, and the options that mod makes unsafe. Being
 * conservative here is cheap: a disabled optimization costs frames, a mixin fighting another mod's
 * transformer over the same method costs the game.
 */
public final class ModCompatibility {
    /** One mod's claim on one option. */
    public static final class Override {
        private final String modId;
        private final String option;
        private final boolean enabled;

        Override(String modId, String option, boolean enabled) {
            this.modId = modId;
            this.option = option;
            this.enabled = enabled;
        }

        public String modId() {
            return this.modId;
        }

        public String option() {
            return this.option;
        }

        public boolean enabled() {
            return this.enabled;
        }
    }

    private static final class Conflict {
        final String modId;
        final String marker;
        final String reason;
        final String[] disables;

        Conflict(String modId, String marker, String reason, String... disables) {
            this.modId = modId;
            this.marker = marker;
            this.reason = reason;
            this.disables = disables;
        }
    }

    private static final List<Conflict> CONFLICTS = conflicts();

    private ModCompatibility() {
    }

    private static List<Conflict> conflicts() {
        Map<String, Conflict> conflicts = new LinkedHashMap<>();

        // Cubic Chunks replaces the chunk column with a cube grid. Every world-shaped assumption in
        // here — sixteen sections per column, an eight-bit section index, one heightmap per column —
        // is wrong under it, and wrong quietly rather than loudly.
        conflicts.put("cubicchunks", new Conflict("Cubic Chunks",
                "io.github.opencubicchunks.cubicchunks.core.asm.CubicChunksCoreContainer",
                "it replaces the chunk column with a cube grid",
                "mixin.chunk",
                "mixin.util.chunk_access",
                "mixin.world.inline_block_access",
                "mixin.world.inline_height",
                "mixin.world.explosions",
                "mixin.entity.collisions",
                "mixin.entity.fast_retrieval"));

        // Sponge rewrites world ticking, block scheduling and inventory handling to hang its event
        // system off them. The overlap is not "both are fast", it is "both replace the same method".
        conflicts.put("sponge", new Conflict("SpongeForge",
                "org.spongepowered.common.SpongeImpl",
                "it rewrites world ticking and inventory handling",
                "mixin.world.tick_scheduler",
                "mixin.block.hopper",
                "mixin.world.block_entity_ticking",
                "mixin.world.explosions"));

        // BetterFps' math transformer rewrites the body of MathHelper.sin/cos with a LaunchWrapper
        // transformer. Ours overwrites the same two methods with a mixin. Whichever runs second wins
        // and the other's table is left allocated and unused, so the only question is which allocation
        // is wasted — and the answer should not depend on transformer ordering.
        conflicts.put("betterfps", new Conflict("BetterFps",
                "guichaguri.betterfps.tweaker.BetterFpsTweaker",
                "its math transformer replaces the same sine table",
                "mixin.math.sine_lut"));

        // FoamFix's coremod rewrites BlockPos' mutable subclasses field by field. Our patch replaces
        // the directional offset methods those subclasses inherit. The two have not been observed to
        // collide, but BlockPos is loaded during bootstrap, before anything could report that they had.
        conflicts.put("foamfix", new Conflict("FoamFix",
                "pl.asie.foamfix.coremod.FoamFixCore",
                "its coremod rewrites BlockPos",
                "mixin.math.fast_blockpos"));

        return Collections.unmodifiableList(new ArrayList<>(conflicts.values()));
    }

    /**
     * Resolves the overrides that apply to this instance.
     *
     * <p>Called once, from {@link EquilibriumConfig#load}.
     */
    public static List<Override> detect() {
        List<Override> overrides = new ArrayList<>();

        for (Conflict conflict : CONFLICTS) {
            if (!isClassPresent(conflict.marker)) {
                continue;
            }

            Equilibrium.LOGGER.warn("{} was detected; {}. The following options will be disabled: {}",
                    conflict.modId, conflict.reason, String.join(", ", conflict.disables));

            for (String option : conflict.disables) {
                overrides.add(new Override(conflict.modId, option, false));
            }
        }

        return overrides;
    }

    /**
     * Deliberately does not initialise the class.
     *
     * <p>Same reasoning as {@code FulgorMixinPlugin}: this runs during coremod setup, where loading a
     * foreign class early can change the order everything else loads in. Whether it exists is all
     * that is being asked.
     */
    private static boolean isClassPresent(String name) {
        try {
            Class.forName(name, false, ModCompatibility.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
