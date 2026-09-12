package com.bdmajora.impetus.core;

import com.bdmajora.fulgor.Fulgor;
import net.minecraft.launchwrapper.Launch;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Registers Impetus' mixin configurations and suppresses superseded lighting engines
// Talks to Mixin directly so the same path works under an installed MixinBooter or the bundled copy
final class ImpetusMixinRegistrar {

    // Maps legacy Phosphor lineage mods to their configs
    // Fulgor replaces the functions below, running both simultaneously would corrupt the lighting
    private static final Map<String, String> SUPERSEDED_LIGHTING_MODS = supersededLightingMods();

    private ImpetusMixinRegistrar() { }

    // Builds the immutable map of conflicting lighting mods to suppress
    private static Map<String, String> supersededLightingMods() {
        Map<String, String> mods = new LinkedHashMap<>();

        // Base Phosphor implementation
        mods.put("phosphor-lighting", "mixins.phosphor.json");

        // Alfheim lighting engine fork
        mods.put("alfheim", "mixins.alfheim.json");

        return Collections.unmodifiableMap(mods);
    }

    // Hijacks first so blacklists land before any config is queued
    static void apply() {
        hijackSupersededLighting();
        for (String config : mixinConfigs()) {
            Mixins.addConfiguration(config);
        }
    }

    // 1. Impetus/Umbra: Reserve early-load slots
    // 2. Coartatio: Must apply before vanilla NBT/ResourceLocation instantiation
    // 3. Fulgor: Injects required lighting fields into World/Chunk
    // 4. Equilibrium: Loads last so Fulgor's reads utilize Equilibrium's chunk cache
    // 5. Extras: Pure feature switches over existing render paths; no ordering constraints
    // 6. Dynamic Lights: Reads the lightmap Fulgor and the chunk builder produce; must not
    //    precede them, and like Extras is otherwise order-independent
    private static List<String> mixinConfigs() {
        return Arrays.asList("mixins.impetus.json", "mixins.umbra.json", "mixins.coartatio.json",
                "mixins.fulgor.json", "mixins.equilibrium.json", "mixins.extras.json",
                "mixins.dynamiclights.json");
    }

    // Blacklists Phosphor and Alfheim configs when their json is on the classpath, since two lighting engines corrupt light
    private static void hijackSupersededLighting() {
        for (Map.Entry<String, String> mod : SUPERSEDED_LIGHTING_MODS.entrySet()) {
            String config = mod.getValue();

            // Presence is inferred from the config being on the classpath rather than from a booter's
            // mod index, which keeps this independent of whichever booter is running.
            if (Launch.classLoader.getResource(config) == null) {
                continue;
            }

            // Warn the user to physically remove the conflicting jar
            Fulgor.LOGGER.warn("{} was detected. Impetus' own lighting engine (Fulgor) replaces it "
                    + "entirely and its patches will be suppressed; you should remove it.", mod.getKey());

            Config.blacklist(config);
        }
    }

}
