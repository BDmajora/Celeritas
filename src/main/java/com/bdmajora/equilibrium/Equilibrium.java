package com.bdmajora.equilibrium;

import com.bdmajora.equilibrium.config.EquilibriumConfig;
import com.bdmajora.equilibrium.config.EquilibriumOptions;
import com.bdmajora.equilibrium.config.Option;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry points for Impetus' general-purpose performance subsystem.
 *
 * <p>Equilibrium is a backport of <a href="https://github.com/CaffeineMC/lithium">Lithium</a> to
 * 1.12.2, taking the same position BetterFps and OptiFine's non-rendering patches occupy on this
 * version but with Lithium's algorithms and Lithium's discipline about vanilla parity. See
 * {@code EQUILIBRIUM_ROADMAP.md} for the feature inventory and what did and did not survive the
 * backport.
 *
 * <p>It is not a mod entry point in the FML sense — every optimization is a mixin gated on an option,
 * and the options are resolved during coremod setup before anything here could run. This class owns
 * the logger and the loaded config so that both have exactly one home.
 *
 * <p>The three sibling subsystems divide the game between them: {@code Coartatio} owns memory,
 * {@code Fulgor} owns lighting, {@code Impetus} itself owns rendering, and Equilibrium owns
 * everything the server thread does. Where two could plausibly claim the same class, the option tree
 * says who wins — see {@code mixin.chunk.serialization}'s absence, which is Coartatio's.
 */
public final class Equilibrium {
    public static final Logger LOGGER = LogManager.getLogger("Equilibrium");

    /**
     * The resolved option tree.
     *
     * <p>Set once by {@link com.bdmajora.equilibrium.mixin.EquilibriumMixinPlugin} during coremod
     * setup and never replaced. Reading it before then would build a second, unshared config, so the
     * accessor loads one rather than returning null — that path is only reachable from tooling that
     * runs outside the game.
     */
    private static EquilibriumConfig config;

    private Equilibrium() {
    }

    public static EquilibriumConfig config() {
        if (config == null) {
            config = EquilibriumConfig.load(EquilibriumConfig.defaultFile());
        }

        return config;
    }

    /** Called by the mixin plugin once it has loaded the file, so nothing loads it twice. */
    public static void setConfig(EquilibriumConfig loaded) {
        config = loaded;
    }

    public static boolean isEnabled(String optionName) {
        return config().isOptionEnabled(optionName);
    }

    /**
     * Human-readable summary of what applied and what did not.
     *
     * <p>Shared by the startup log line and {@code /equilibrium}. The interesting part is not the
     * count of enabled options but the list of disabled ones, because that is the answer to "why is
     * this not helping".
     */
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

    private static void appendGroup(List<String> lines, String heading, List<String> members) {
        if (members.isEmpty()) {
            return;
        }

        lines.add("  " + heading + ":");

        for (String member : members) {
            lines.add("    " + member);
        }
    }

    /** The single line Impetus adds to the F3 overlay when the option is on. */
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
