package com.bdmajora.equilibrium.config;

import com.bdmajora.equilibrium.Equilibrium;
import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The loaded option tree, resolved against the user's config file and the mods that are present.
 *
 * <p>A direct port of Lithium's {@code LithiumConfig}, with two deliberate divergences.
 *
 * <p>The first is where the tree comes from: Lithium reads a properties resource its build generates
 * from annotations, we read {@link EquilibriumOptions}. The behaviour is identical, the declaration
 * just lives in Java.
 *
 * <p>The second is how mods override options. Lithium reads a {@code lithium:options} block out of
 * each mod's metadata, which the loader has already parsed by the time its mixin plugin runs. On
 * 1.12.2 nothing has parsed anything yet — this runs during coremod setup, before FML has a mod list
 * — so overrides come from {@link ModCompatibility} instead, which detects the mods that actually
 * conflict by looking for their classes.
 *
 * <p>Everything is read at coremod time and never re-read, so a change made in the GUI applies on the
 * next launch. The GUI says so.
 */
public class EquilibriumConfig {
    private static final String FILE_NAME = "equilibrium.properties";

    private final Map<String, Option> options = new HashMap<>();

    /** Only the options that have dependencies, so the fixpoint loop does not walk the whole tree. */
    private final Set<Option> optionsWithDependencies = new LinkedHashSet<>();

    /** Where {@link #save()} writes. Null only if the config directory could not be resolved. */
    private Path file;

    private EquilibriumConfig() {
        for (EquilibriumOptions.Entry entry : EquilibriumOptions.entries().values()) {
            this.addMixinRule(entry.name(), entry.enabledByDefault());
        }

        for (EquilibriumOptions.Entry entry : EquilibriumOptions.entries().values()) {
            for (Map.Entry<String, Boolean> dependency : entry.dependencies().entrySet()) {
                this.addRuleDependency(entry.name(), dependency.getKey(), dependency.getValue());
            }
        }
    }

    /**
     * Loads the configuration file from the given location, creating it if it does not exist.
     *
     * <p>The file that gets written holds every option with its default commented out, rather than
     * Lithium's empty file. A 1.12.2 user reaching for a properties file is usually doing so because
     * something broke and they want to bisect it, and a file that lists what can be turned off is
     * worth more to them than one that does not.
     */
    public static EquilibriumConfig load(Path file) {
        EquilibriumConfig config = new EquilibriumConfig();
        config.file = file;

        if (Files.isRegularFile(file)) {
            Properties props = new Properties();

            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                Equilibrium.LOGGER.error("Could not read {}, falling back to defaults", file, e);
            }

            config.readProperties(props);
        } else {
            config.save();
        }

        for (ModCompatibility.Override override : ModCompatibility.detect()) {
            config.applyModOverride(override);
        }

        config.applyDependencies();

        return config;
    }

    /** The directory the config file lives in, mirroring how Fulgor and Coartatio resolve theirs. */
    public static Path defaultFile() {
        File home = Launch.minecraftHome;
        Path dir = (home == null ? Paths.get(".") : home.toPath()).resolve("config");

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Equilibrium.LOGGER.warn("Could not create {}, configuration will not persist", dir, e);
        }

        return dir.resolve(FILE_NAME);
    }

    private void addMixinRule(String mixin, boolean enabled) {
        if (this.options.put(mixin, new Option(mixin, enabled, false)) != null) {
            throw new IllegalStateException("Mixin rule already defined: " + mixin);
        }
    }

    private void addRuleDependency(String rule, String dependency, boolean requiredValue) {
        Option option = this.options.get(rule);
        Option dependencyOption = this.options.get(dependency);

        // EquilibriumOptions validates this at class-init, so reaching either branch means the tree
        // and this loader have gone out of sync rather than that a user typed something wrong.
        if (option == null || dependencyOption == null) {
            Equilibrium.LOGGER.error("Dependency '{} depends on {}={}' names an option that does not exist, skipping",
                    rule, dependency, requiredValue);
            return;
        }

        option.addDependency(dependencyOption, requiredValue);
        this.optionsWithDependencies.add(option);
    }

    private void readProperties(Properties props) {
        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();

            Option option = this.options.get(key);

            if (option == null) {
                Equilibrium.LOGGER.warn("No configuration key exists with name '{}', ignoring", key);
                continue;
            }

            boolean enabled;

            if (value.equalsIgnoreCase("true")) {
                enabled = true;
            } else if (value.equalsIgnoreCase("false")) {
                enabled = false;
            } else {
                Equilibrium.LOGGER.warn("Invalid value '{}' encountered for configuration key '{}', ignoring",
                        value, key);
                continue;
            }

            option.setEnabled(enabled, true);
        }
    }

    /**
     * Applies one mod's override.
     *
     * <p>Disabling wins over enabling: if two mods disagree about an option, the one that says the
     * patch is unsafe is the one to believe, because the cost of being wrong is asymmetric.
     */
    void applyModOverride(ModCompatibility.Override override) {
        Option option = this.options.get(override.option());

        if (option == null && !override.option().startsWith("mixin.")) {
            option = this.options.get("mixin." + override.option());
        }

        if (option == null) {
            Equilibrium.LOGGER.warn("Mod '{}' attempted to override option '{}', which doesn't exist, ignoring",
                    override.modId(), override.option());
            return;
        }

        // A user who has explicitly set an option has had the last word; a mod does not get to
        // silently undo it. Lithium takes the opposite view, but Lithium's overrides come from mod
        // metadata the user chose to install, whereas ours come from detection the user never asked
        // for, and being overridden without explanation is worse than a mod incompatibility warning.
        if (option.isUserDefined()) {
            Equilibrium.LOGGER.warn("{} is installed and wants '{}={}', but the config file sets it to {}. "
                            + "Leaving the configured value; expect problems if it turns out to be wrong.",
                    override.modId(), override.option(), override.enabled(), option.isEnabled());
            return;
        }

        if (!override.enabled() && option.isEnabled()) {
            option.clearModsDefiningValue();
        }

        if (!override.enabled() || option.isEnabled() || option.getDefiningMods().isEmpty()) {
            option.addModOverride(override.enabled(), override.modId());
        }
    }

    /**
     * Resolves the effective option for a mixin, by walking its package path from the root down.
     *
     * <p>The first disabled rule on the way down wins, otherwise the deepest rule found wins. So
     * {@code mixin.world=false} disables everything under {@code world} regardless of what those
     * children say, which is what a user turning off a whole category means by it.
     *
     * @return the governing option, or null if nothing in the tree matched
     */
    public Option getEffectiveOptionForMixin(String mixinClassName) {
        int lastSplit = 0;
        int nextSplit;

        Option rule = null;

        while ((nextSplit = mixinClassName.indexOf('.', lastSplit)) != -1) {
            String key = "mixin." + mixinClassName.substring(0, nextSplit);

            Option candidate = this.options.get(key);

            if (candidate != null) {
                rule = candidate;

                if (!rule.isEnabled()) {
                    return rule;
                }
            }

            lastSplit = nextSplit + 1;
        }

        return rule;
    }

    public boolean isOptionEnabled(String optionName) {
        Option option = this.options.get(optionName);
        return option != null && option.isEnabledRecursive(this);
    }

    /** Used by the options screen, which edits values and then calls {@link #save()}. */
    public void setOptionEnabled(String optionName, boolean enabled) {
        Option option = this.options.get(optionName);

        if (option != null) {
            option.setEnabled(enabled, true);
        }
    }

    public Option getOption(String optionName) {
        return this.options.get(optionName);
    }

    public Option getParent(Option option) {
        String optionName = option.getName();
        int split = optionName.lastIndexOf('.');

        return split == -1 ? null : this.options.get(optionName.substring(0, split));
    }

    public int getOptionCount() {
        return this.options.size();
    }

    public int getOptionOverrideCount() {
        int count = 0;

        for (Option option : this.options.values()) {
            if (option.isOverridden()) {
                count++;
            }
        }

        return count;
    }

    /**
     * Turns off every option whose dependencies are not met, repeating until nothing changes.
     *
     * <p>One pass is not enough: disabling an option can break a dependency of another option that
     * was already visited. This terminates because each pass that changes anything disables at least
     * one option and options are never re-enabled.
     */
    private void applyDependencies() {
        //noinspection StatementWithEmptyBody
        while (this.applyDependenciesOnce()) {
        }
    }

    private boolean applyDependenciesOnce() {
        boolean changed = false;
        Logger logger = Equilibrium.LOGGER;

        for (Option optionWithDependency : this.optionsWithDependencies) {
            changed |= optionWithDependency.disableIfDependenciesNotMet(logger, this);
        }

        return changed;
    }

    /** Writes every option out with its description, preserving whatever the user has set. */
    public void save() {
        if (this.file == null) {
            return;
        }

        try {
            Path parent = this.file.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (OutputStream stream = Files.newOutputStream(this.file);
                 Writer writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                this.write(writer);
            }
        } catch (IOException e) {
            Equilibrium.LOGGER.warn("Could not write {}", this.file, e);
        }
    }

    private void write(Writer writer) throws IOException {
        writer.write("# Equilibrium — Impetus' general-purpose performance subsystem.\n");
        writer.write("#\n");
        writer.write("# Every key here switches one group of patches on or off. A key governs its own\n");
        writer.write("# patches and every key beneath it, so 'mixin.world=false' disables all of the\n");
        writer.write("# world optimizations regardless of what the individual keys under it say.\n");
        writer.write("#\n");
        writer.write("# Changes take effect on the next launch. Deleting a line restores its default.\n");
        writer.write("#\n");
        writer.write("# If something in the game misbehaves and you suspect Equilibrium, the fastest way\n");
        writer.write("# to find out is to set the top-level key of a category to false and work down.\n");
        writer.write("\n");

        String category = null;

        for (EquilibriumOptions.Entry entry : EquilibriumOptions.entries().values()) {
            Option option = this.options.get(entry.name());

            if (!entry.category().equals(category)) {
                category = entry.category();
                writer.write("\n");
                writer.write("# ---------------------------------------------------------------------------\n");
                writer.write("# " + category + "\n");
                writer.write("# ---------------------------------------------------------------------------\n");
            }

            writer.write("\n");

            for (String line : wrap(entry.description(), 74)) {
                writer.write("# " + line + "\n");
            }

            if (entry.nonVanillaBehaviour() != null) {
                writer.write("#\n");
                writer.write("# Differs from vanilla:\n");

                for (String line : wrap(entry.nonVanillaBehaviour(), 72)) {
                    writer.write("#   " + line + "\n");
                }
            }

            for (Map.Entry<String, Boolean> dependency : entry.dependencies().entrySet()) {
                writer.write("# Requires: " + dependency.getKey() + "=" + dependency.getValue() + "\n");
            }

            writer.write("# Default: " + entry.enabledByDefault() + "\n");

            // Options the user has never touched stay commented out, so the file keeps saying what the
            // default is rather than freezing today's default into the user's config forever.
            if (option != null && option.isUserDefined()) {
                writer.write(entry.name() + "=" + option.isEnabled() + "\n");
            } else {
                writer.write("#" + entry.name() + "=" + entry.enabledByDefault() + "\n");
            }
        }
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();

        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }

            if (line.length() > 0) {
                line.append(' ');
            }

            line.append(word);
        }

        if (line.length() > 0) {
            lines.add(line.toString());
        }

        return lines;
    }
}
