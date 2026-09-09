package com.bdmajora.equilibrium.mixin;

import com.bdmajora.equilibrium.Equilibrium;
import com.bdmajora.equilibrium.config.EquilibriumConfig;
import com.bdmajora.equilibrium.config.Option;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

// decides which Equilibrium mixins apply, by resolving each one's package path against the option tree
// ported from Lithium's LithiumMixinPlugin; the structure is worth restating because it differs from
// the other two subsystems in this project - FulgorMixinPlugin and CoartatioMixinPlugin both switch on
// a hand-written list of mixin names, which is fine when there are a dozen
// here there are far more than a dozen, and the mapping between a mixin and its switch is not a table
// anyone maintains: it is the package the mixin lives in, so adding a mixin under an existing option
// needs no change to this file at all
public class EquilibriumMixinPlugin implements IMixinConfigPlugin {
    private static final String MIXIN_PACKAGE_ROOT = "com.bdmajora.equilibrium.mixin.";

    // kill switch for bisecting a crash without editing the config file
    // Lithium has the same property under lithium.test.disable_all_mixins; it is the first thing to
    // reach for when a modpack crashes on startup, because it answers "is this us" in one launch
    // argument
    private static final String DISABLE_ALL_MIXINS_PROPERTY = "equilibrium.disable_all_mixins";

    public static final boolean DISABLE_ALL_MIXINS = Boolean.parseBoolean(System.getProperty(DISABLE_ALL_MIXINS_PROPERTY));

    private static EquilibriumConfig config;

    @Override
    public void onLoad(String mixinPackage) {
        if (DISABLE_ALL_MIXINS) {
            Equilibrium.LOGGER.warn("All Equilibrium mixins are disabled via -D{}=true", DISABLE_ALL_MIXINS_PROPERTY);
            return;
        }

        if (config != null) {
            return;
        }

        try {
            config = EquilibriumConfig.load(EquilibriumConfig.defaultFile());
        } catch (Exception e) {
            throw new RuntimeException("Could not load configuration file for Equilibrium", e);
        }

        Equilibrium.setConfig(config);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (DISABLE_ALL_MIXINS) {
            return false;
        }

        if (!mixinClassName.startsWith(MIXIN_PACKAGE_ROOT)) {
            Equilibrium.LOGGER.error("Expected mixin '{}' to start with package root '{}', treating as foreign and "
                    + "disabling", mixinClassName, MIXIN_PACKAGE_ROOT);
            return false;
        }

        String mixin = mixinClassName.substring(MIXIN_PACKAGE_ROOT.length());

        Option option = config.getEffectiveOptionForMixin(mixin);

        // An unmatched mixin means someone added a package without adding its option. Refusing to
        // apply it is the safe reading: an optimization nobody can turn off is worse than one that
        // never ran, and the log line says exactly what to add.
        if (option == null) {
            Equilibrium.LOGGER.error("No rules matched mixin '{}', treating as foreign and disabling", mixin);
            return false;
        }

        return option.isEnabled();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
