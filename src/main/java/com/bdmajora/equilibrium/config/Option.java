package com.bdmajora.equilibrium.config;

import it.unimi.dsi.fastutil.objects.Object2BooleanLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

// one node of the option tree: a single mixin.* rule
// ported from Lithium's Option unchanged in behaviour
// an option carries three independent notions of "who set this" - the built-in default, a user
// override from config/equilibrium.properties, and an override contributed by another mod
// they are kept apart so the log can say why a mixin was skipped, which is the first thing anyone
// needs when a patch does not apply
public class Option {
    private final String name;

    // options this one requires, and the value each must hold
    // Object2BooleanLinkedOpenHashMap rather than a plain map because the iteration order decides
    // which unmet dependency gets reported first, and a stable report is worth more than the handful
    // of bytes a linked map costs - there are at most a couple of hundred options
    private Object2BooleanLinkedOpenHashMap<Option> dependencies;

    // Mods that have overridden this option, or null if none have.
    private Set<String> modDefined = null;

    private boolean enabled;
    private boolean userDefined;

    public Option(String name, boolean enabled, boolean userDefined) {
        this.name = name;
        this.enabled = enabled;
        this.userDefined = userDefined;
    }

    public void setEnabled(boolean enabled, boolean userDefined) {
        this.enabled = enabled;
        this.userDefined = userDefined;
    }

    public void addModOverride(boolean enabled, String modId) {
        this.enabled = enabled;

        if (this.modDefined == null) {
            this.modDefined = new LinkedHashSet<>();
        }

        this.modDefined.add(modId);
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    // whether this option and every option above it in the package tree are enabled
    // disabling mixin.world has to disable mixin.world.explosions even though the latter is still
    // nominally true, otherwise a user turning off a whole category would leave its children applied
    public boolean isEnabledRecursive(EquilibriumConfig config) {
        return this.enabled && (config.getParent(this) == null || config.getParent(this).isEnabledRecursive(config));
    }

    public boolean isOverridden() {
        return this.isUserDefined() || this.isModDefined();
    }

    public boolean isUserDefined() {
        return this.userDefined;
    }

    public boolean isModDefined() {
        return this.modDefined != null;
    }

    public String getName() {
        return this.name;
    }

    public void clearModsDefiningValue() {
        this.modDefined = null;
    }

    public Collection<String> getDefiningMods() {
        return this.modDefined != null ? Collections.unmodifiableCollection(this.modDefined) : Collections.<String>emptyList();
    }

    public void addDependency(Option dependencyOption, boolean requiredValue) {
        if (this.dependencies == null) {
            this.dependencies = new Object2BooleanLinkedOpenHashMap<>(1);
        }
        this.dependencies.put(dependencyOption, requiredValue);
    }

    // Turns this option off if any dependency is not in its required state
    // Returns whether the option changed, so the caller knows to run another pass — one pass is not enough,
    // because disabling an option can break a dependency of an option already visited
    public boolean disableIfDependenciesNotMet(EquilibriumConfig config) {
        if (this.dependencies != null && this.isEnabled()) {
            for (Object2BooleanMap.Entry<Option> dependency : this.dependencies.object2BooleanEntrySet()) {
                Option option = dependency.getKey();
                boolean requiredValue = dependency.getBooleanValue();
                boolean enabledRecursive = option.isEnabledRecursive(config);

                if (enabledRecursive != requiredValue) {
                    this.enabled = false;
                    return true;
                }
            }
        }

        return false;
    }
}
