package org.taumc.celeritas.core;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.jetbrains.annotations.Nullable;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@IFMLLoadingPlugin.Name("Celeritas")
@IFMLLoadingPlugin.MCVersion("1.12.2")
public class CeleritasLoadingPlugin implements IFMLLoadingPlugin, IEarlyMixinLoader {
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
        // mixins.ausm.json activates the grafted AUSM shader pipeline's render hooks (non-fatal: defaultRequire=0).
        return Arrays.asList("mixins.celeritas.json", "mixins.iris.json", "mixins.ausm.json");
    }
}
