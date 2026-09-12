package com.bdmajora.impetus.core;

import com.bdmajora.impetus.booter.BooterBootstrap;
import com.bdmajora.impetus.booter.BooterCore;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.List;
import java.util.Map;

// Impetus' single coremod entry point. Forge's FMLCorePlugin manifest attribute takes exactly one
// class, so this covers both jobs: bringing up Mixin when nothing else has, and registering Impetus'
// own mixin configurations.
//
// Holds no org.spongepowered.asm reference of its own. BooterCore and ImpetusMixinRegistrar are the
// Mixin-facing halves and are only ever invoked once Mixin is known to be on the classpath, which is
// what lets Impetus load on a Forge install with no MixinBooter present.
//
// Sorted just after MixinBooter's Integer.MIN_VALUE + 1 so that an installed MixinBooter always wins
// the race to claim the Mixin service and we observe it rather than compete with it.
@IFMLLoadingPlugin.Name("Impetus")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(Integer.MIN_VALUE + 2)
public class ImpetusLoadingPlugin implements IFMLLoadingPlugin {

    public ImpetusLoadingPlugin() {
        // Must happen in the constructor: Mixin has to be live before any other coremod's injectData runs.
        if (BooterBootstrap.initialize() == BooterBootstrap.MIXIN_OWNED) {
            BooterCore.initialize();
        }
    }

    // Runs the booter's own injection when Impetus owns Mixin, then registers Impetus' configs under either booter
    @Override
    public void injectData(Map<String, Object> data) {
        if (BooterBootstrap.state() == BooterBootstrap.MIXIN_OWNED) {
            Object coremodList = data.get("coremodList");
            if (!(coremodList instanceof List)) {
                throw new RuntimeException("Blackboard property 'coremodList' must be of type List, early loaders were not able to be gathered");
            }
            BooterCore.injectData((List<?>) coremodList);
        }
        // Runs under either booter; Mixin is bootstrapped by this point in both paths.
        ImpetusMixinRegistrar.apply();
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

    // Required by IFMLLoadingPlugin; left blank as Impetus does not use access transformers
    @Override
    public String getAccessTransformerClass() {
        return null;
    }

}
