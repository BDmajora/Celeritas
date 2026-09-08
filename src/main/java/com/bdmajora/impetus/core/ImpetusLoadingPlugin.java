package com.bdmajora.impetus.core;

import com.bdmajora.fulgor.Fulgor;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.jetbrains.annotations.Nullable;
import zone.rong.mixinbooter.Context;
import zone.rong.mixinbooter.IEarlyMixinLoader;
import zone.rong.mixinbooter.IMixinConfigHijacker;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@IFMLLoadingPlugin.Name("Impetus")
@IFMLLoadingPlugin.MCVersion("1.12.2")
public class ImpetusLoadingPlugin implements IFMLLoadingPlugin, IEarlyMixinLoader, IMixinConfigHijacker {
    
    // Maps legacy Phosphor lineage mods to their configs
    // Fulgor replaces the functions below, running both simultaneously would corrupt the lighting
    private static final Map<String, String> SUPERSEDED_LIGHTING_MODS = supersededLightingMods();

    // Builds the immutable map of conflicting lighting mods to suppress
    private static Map<String, String> supersededLightingMods() {
        Map<String, String> mods = new LinkedHashMap<>();
        
        // Base Phosphor implementation
        mods.put("phosphor-lighting", "mixins.phosphor.json");
        
        // Alfheim lighting engine fork
        mods.put("alfheim", "mixins.alfheim.json");
        
        return Collections.unmodifiableMap(mods);
    }

    // Required by IFMLLoadingPlugin; left blank as Impetus has no ASM transformers
    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    // Required by IFMLLoadingPlugin; left blank as Impetus does not use a mod container
    @Override
    public String getModContainerClass() {
        return null;
    }

    // Required by IFMLLoadingPlugin; left blank as Impetus does not use a setup class
    @Override
    public String getSetupClass() {
        return null;
    }

    // Required by IFMLLoadingPlugin; left blank as Impetus does not inject custom data
    @Override
    public void injectData(Map<String, Object> map) {

    }

    // Required by IFMLLoadingPlugin; left blank as Impetus does not use access transformers
    @Override
    public String getAccessTransformerClass() {
        return null;
    }

    @Override
    public List<String> getMixinConfigs() {
        // 1. Impetus/Umbra: Reserve early-load slots
        // 2. Coartatio: Must apply before vanilla NBT/ResourceLocation instantiation
        // 3. Fulgor: Injects required lighting fields into World/Chunk
        // 4. Equilibrium: Loads last so Fulgor's reads utilize Equilibrium's chunk cache
        // 5. Extras: Pure feature switches over existing render paths; no ordering constraints
        // 6. Dynamic Lights: Reads the lightmap Fulgor and the chunk builder produce; must not
        //    precede them, and like Extras is otherwise order-independent
        return Arrays.asList("mixins.impetus.json", "mixins.umbra.json", "mixins.coartatio.json",
                "mixins.fulgor.json", "mixins.equilibrium.json", "mixins.extras.json",
                "mixins.dynamiclights.json");
    }

    // Extracts just the config JSON names to pass to the hijacker
    @Override
    public Set<String> getHijackedMixinConfigs() {
        return new HashSet<>(SUPERSEDED_LIGHTING_MODS.values());
    }

    // Checks the environment to execute the config suppression
    @Override
    public Set<String> getHijackedMixinConfigs(Context context) {
        Set<String> hijacked = new HashSet<>();

        // Scan early coremod jars for conflicting lighting engines
        for (Map.Entry<String, String> mod : SUPERSEDED_LIGHTING_MODS.entrySet()) {
            
            // Check if the conflicting mod ID is currently installed
            if (context.isModPresent(mod.getKey())) {
                
                // Warn the user to physically remove the conflicting jar
                Fulgor.LOGGER.warn("{} was detected. Impetus' own lighting engine (Fulgor) replaces it "
                        + "entirely and its patches will be suppressed; you should remove it.", mod.getKey());
                
                // Add the alternative mod's config to the active suppression list
                hijacked.add(mod.getValue());
            }
        }

        return hijacked;
    }
}