package com.bdmajora.equilibrium.config;

import it.unimi.dsi.fastutil.objects.Object2BooleanLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

// One node of the option tree, a single mixin.* rule from Lithium's Option; default, user override and mod override are tracked apart so the log can say why a mixin was skipped
public class Option {
    // Full dotted rule name, e.g. mixin.world.explosions
    private final String name;

    // Options this one requires and the value each must hold; linked because iteration order decides which unmet dependency is reported first
    private Object2BooleanLinkedOpenHashMap<Option> dependencies;

    // Mods that have overridden this option, or null if none have.
    private Set<String> modDefined = null;

    // Current value after defaults, user config, mod overrides and dependency resolution
    private boolean enabled;

    // True once the user's own config file set this, which keeps it uncommented on save
    private boolean userDefined;

    // Built once per rule while the option tree is assembled
    public Option(String name, boolean enabled, boolean userDefined) {
        this.name = name;
        this.enabled = enabled;
        this.userDefined = userDefined;
    }

    // Used by the config loader and the options screen; userDefined marks it as the user's own choice
    public void setEnabled(boolean enabled, boolean userDefined) {
        this.enabled = enabled;
        this.userDefined = userDefined;
    }

    // Records that a mod forced this value, keeping every contributor so the log can name them all
    public void addModOverride(boolean enabled, String modId) {
        this.enabled = enabled;

        if (this.modDefined == null) {
            this.modDefined = new LinkedHashSet<>();
        }

        this.modDefined.add(modId);
    }

    // This node's own value, ignoring ancestors; see isEnabledRecursive for the effective one
    public boolean isEnabled() {
        return this.enabled;
    }

    // This option and every ancestor; disabling mixin.world must also disable mixin.world.explosions even though the child is nominally true
    public boolean isEnabledRecursive(EquilibriumConfig config) {
        return this.enabled && (config.getParent(this) == null || config.getParent(this).isEnabledRecursive(config));
    }

    // True when anything moved this off its built-in default
    public boolean isOverridden() {
        return this.isUserDefined() || this.isModDefined();
    }

    // Whether the value came from the user's config file
    public boolean isUserDefined() {
        return this.userDefined;
    }

    // Whether any mod contributed an override
    public boolean isModDefined() {
        return this.modDefined != null;
    }

    // The dotted rule name this option was registered under
    public String getName() {
        return this.name;
    }

    // Drops mod overrides so a reload starts from the user's own values again
    public void clearModsDefiningValue() {
        this.modDefined = null;
    }

    // Mod ids that overrode this, for the stats command; empty rather than null when none did
    public Collection<String> getDefiningMods() {
        return this.modDefined != null ? Collections.unmodifiableCollection(this.modDefined) : Collections.<String>emptyList();
    }

    // Map is allocated lazily at size 1, since most options declare no dependencies at all
    public void addDependency(Option dependencyOption, boolean requiredValue) {
        if (this.dependencies == null) {
            this.dependencies = new Object2BooleanLinkedOpenHashMap<>(1);
        }
        this.dependencies.put(dependencyOption, requiredValue);
    }

    // Turns this option off if any dependency is unmet; returns whether it changed so the caller knows to sweep again
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
