package com.bdmajora.equilibrium;

import com.bdmajora.equilibrium.config.EquilibriumConfig;
import com.bdmajora.equilibrium.config.EquilibriumOptions;
import com.bdmajora.equilibrium.config.Option;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

// Lithium backport for 1.12.2; owns the logger and loaded config as the single shared instance
// Not an FML entry point - mixins are gated on options resolved earlier during coremod setup
public final class Equilibrium {
    public static final Logger LOGGER = LogManager.getLogger("Equilibrium");

    // Set once by EquilibriumMixinPlugin during coremod setup; accessor lazy-loads instead of
    // returning null so tooling running outside the game still gets a usable config
    private static EquilibriumConfig config;

    private Equilibrium() {
    }

    // Loads on first use, so callers outside the mixin plugin need not order themselves after it
    public static EquilibriumConfig config() {
        if (config == null) {
            config = EquilibriumConfig.load(EquilibriumConfig.defaultFile());
        }

        return config;
    }

    // Called by the mixin plugin once it has loaded the file, so nothing loads it twice
    public static void setConfig(EquilibriumConfig loaded) {
        config = loaded;
    }

    // Convenience for code that wants one rule without touching the config object
    public static boolean isEnabled(String optionName) {
        return config().isOptionEnabled(optionName);
    }

    // Shared by the startup log line and /equilibrium; the disabled list matters more than the count
    public static List<String> statistics() {
        EquilibriumConfig config = config();

        List<String> disabledByUser = new ArrayList<>();
        List<String> disabledByMod = new ArrayList<>();
        List<String> disabledByDependency = new ArrayList<>();
        int enabled = 0;

        for (EquilibriumOptions.Entry entry : EquilibriumOptions.entries().values()) {
            Option option = config.getOption(entry.name());

            if (option == null) {
                continue;
            }

            if (option.isEnabledRecursive(config)) {
                enabled++;
            } else if (option.isUserDefined()) {
                disabledByUser.add(entry.name());
            } else if (option.isModDefined()) {
                disabledByMod.add(entry.name() + " (" + String.join(", ", option.getDefiningMods()) + ")");
            } else if (entry.enabledByDefault()) {
                // On by default but off now, and nobody claimed it: a dependency of it was disabled.
                disabledByDependency.add(entry.name());
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add("Equilibrium: " + enabled + " of " + config.getOptionCount() + " optimizations active");

        appendGroup(lines, "Disabled in the config file", disabledByUser);
        appendGroup(lines, "Disabled for mod compatibility", disabledByMod);
        appendGroup(lines, "Disabled because a dependency is off", disabledByDependency);

        return lines;
    }

    // Appends a heading and its indented members, or nothing at all when the group is empty
    private static void appendGroup(List<String> lines, String heading, List<String> members) {
        if (members.isEmpty()) {
            return;
        }

        lines.add("  " + heading + ":");

        for (String member : members) {
            lines.add("    " + member);
        }
    }

    // The single line Impetus adds to the F3 overlay when the option is on
    public static String debugOverlayLine() {
        EquilibriumConfig config = config();

        int enabled = 0;

        for (EquilibriumOptions.Entry entry : EquilibriumOptions.entries().values()) {
            Option option = config.getOption(entry.name());

            if (option != null && option.isEnabledRecursive(config)) {
                enabled++;
            }
        }

        return String.format("Equilibrium: %d/%d active (/equilibrium for detail)",
                enabled, config.getOptionCount());
    }
}
