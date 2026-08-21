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
    /**
     * Other mods in the Phosphor lineage, and the mixin config each one registers.
     *
     * <p>Fulgor replaces all of them. Running two of these together is not a degraded experience, it is
     * two engines redefining {@code Chunk.getLightFor} and {@code World.checkLightFor} with different
     * ideas about when propagation happens — so the second one to load either fails to apply or
     * corrupts light. Suppressing the other config is the only outcome that leaves a working game.
     */
    private static final Map<String, String> SUPERSEDED_LIGHTING_MODS = supersededLightingMods();

    private static Map<String, String> supersededLightingMods() {
        Map<String, String> mods = new LinkedHashMap<>();
        mods.put("phosphor-lighting", "mixins.phosphor.json");
        mods.put("alfheim", "mixins.alfheim.json");
        return Collections.unmodifiableMap(mods);
    }

    @Override
    public @Nullable String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public @Nullable String getModContainerClass() {
        return null;
    }

    @Override
    public @Nullable String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> map) {

    }

    @Override
    public @Nullable String getAccessTransformerClass() {
        return null;
    }

    @Override
    public List<String> getMixinConfigs() {
        // The Iris config is currently inert (no mixins yet); registering it here reserves the early-load slot so the
        // rendering-integration phases can add client mixins without further coremod changes.
        // AUSM's runtime pipeline is deactivated (LWJGL3-oriented; incompatible with Impetus' LWJGL2 abstraction).
        // The shader pipeline is Iris-native. mixins.iris.json carries the terrain-override mixin.
        //
        // Coartatio (the memory subsystem) has to load early: it replaces the backing collections of
        // NBTTagCompound and ResourceLocation, both of which are constructed before mod loading
        // begins, and anything built before the mixin applies keeps the vanilla layout for its
        // lifetime.
        //
        // Fulgor (the lighting subsystem) has to load early for a different reason: it adds fields to
        // World and Chunk, and a world constructed before the mixin applies would have no lighting
        // engine at all.
        //
        // Equilibrium (the general performance subsystem) is last of the four, which matters for one
        // class: it overwrites World.getChunk to route through its own cache, and Fulgor's World
        // mixin reads chunks. Loading Equilibrium after Fulgor means Fulgor's reads go through the
        // cache rather than the other way round, which is the order that leaves both correct.
        return Arrays.asList("mixins.impetus.json", "mixins.iris.json", "mixins.coartatio.json",
                "mixins.fulgor.json", "mixins.equilibrium.json");
    }

    @Override
    public Set<String> getHijackedMixinConfigs() {
        return new HashSet<>(SUPERSEDED_LIGHTING_MODS.values());
    }

    /**
     * Suppresses a superseded lighting mod's config, but only when that mod is actually present.
     *
     * <p>The context's mod list is built from coremod jars at this stage, which is exactly the set this
     * matters for — every mod in this lineage ships as a coremod, because none of them can work
     * otherwise.
     */
    @Override
    public Set<String> getHijackedMixinConfigs(Context context) {
        Set<String> hijacked = new HashSet<>();

        for (Map.Entry<String, String> mod : SUPERSEDED_LIGHTING_MODS.entrySet()) {
            if (context.isModPresent(mod.getKey())) {
                Fulgor.LOGGER.warn("{} was detected. Impetus' own lighting engine (Fulgor) replaces it "
                        + "entirely and its patches will be suppressed; you should remove it.", mod.getKey());
                hijacked.add(mod.getValue());
            }
        }

        return hijacked;
    }
}
