package com.bdmajora.impetus.core;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.jetbrains.annotations.Nullable;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@IFMLLoadingPlugin.Name("Impetus")
@IFMLLoadingPlugin.MCVersion("1.12.2")
public class ImpetusLoadingPlugin implements IFMLLoadingPlugin, IEarlyMixinLoader {
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
        return Arrays.asList("mixins.impetus.json", "mixins.iris.json", "mixins.coartatio.json");
    }
}
