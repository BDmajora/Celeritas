package com.bdmajora.equilibrium.config;

import com.bdmajora.equilibrium.Equilibrium;
import net.minecraft.launchwrapper.Launch;

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

// Port of Lithium's LithiumConfig; the tree comes from EquilibriumOptions (Java, not a generated resource) and mod overrides from ModCompatibility's class detection, since no mod list exists at coremod time
public class EquilibriumConfig {
    private static final String FILE_NAME = "equilibrium.properties";

    private final Map<String, Option> options = new HashMap<>();

    // Only the options that have dependencies, so the fixpoint loop does not walk the whole tree
    private final Set<Option> optionsWithDependencies = new LinkedHashSet<>();

    // Where save() writes; null only if the config directory could not be resolved
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

    // The written file lists every option with its default commented out (unlike Lithium's empty file), since a user opening it is usually bisecting a bug
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

    // Config file location resolved like Fulgor and Coarctatio; Launch.minecraftHome is null under a test harness so the working directory stands in, and the directory is created eagerly so save() need not care
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

    // Registers one rule; a duplicate key means the option tree declares a mixin twice, a programming error, so it throws instead of overwriting
    private void addMixinRule(String mixin, boolean enabled) {
        if (this.options.put(mixin, new Option(mixin, enabled, false)) != null) {
            throw new IllegalStateException("Mixin rule already defined: " + mixin);
        }
    }

    // Links a rule to one it requires, so applyDependencies can switch it off when the parent is off
    private void addRuleDependency(String rule, String dependency, boolean requiredValue) {
        Option option = this.options.get(rule);
        Option dependencyOption = this.options.get(dependency);

        // EquilibriumOptions validates this at class-init, so reaching either branch means the tree and this loader drifted, not user error
        if (option == null || dependencyOption == null) {
            Equilibrium.LOGGER.error("Dependency '{} depends on {}={}' names an option that does not exist, skipping",
                    rule, dependency, requiredValue);
            return;
        }

        option.addDependency(dependencyOption, requiredValue);
        this.optionsWithDependencies.add(option);
    }

    // Folds a loaded properties file onto the defaults; unknown keys are warned and skipped so an old config still loads
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

    // Applies one installed mod's request to force an option; disabling wins over enabling, since wrongly keeping a patch (crash) is worse than wrongly dropping one (lost perf)
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

        // A user who explicitly set an option has the last word; unlike Lithium, our overrides come from detection the user never asked for, and silent overriding is worse than an incompatibility warning
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

    // Finds the option governing a mixin class by trying every package prefix as "mixin.<prefix>"; the first DISABLED rule short-circuits (mixin.world=false kills everything under it), else the deepest rule wins, null means no opinion
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

    // Recursive because a child is only truly on when every ancestor is too
    public boolean isOptionEnabled(String optionName) {
        Option option = this.options.get(optionName);
        return option != null && option.isEnabledRecursive(this);
    }

    // Used by the options screen, which edits in place then save()s; an unknown name is a silent no-op since a miss means the screen and tree drifted
    public void setOptionEnabled(String optionName, boolean enabled) {
        Option option = this.options.get(optionName);

        if (option != null) {
            option.setEnabled(enabled, true);
        }
    }

    // Exact-name lookup; null when nothing declares that key
    public Option getOption(String optionName) {
        return this.options.get(optionName);
    }

    // Parent is the name up to the last dot, so mixin.world.foo yields mixin.world
    public Option getParent(Option option) {
        String optionName = option.getName();
        int split = optionName.lastIndexOf('.');

        return split == -1 ? null : this.options.get(optionName.substring(0, split));
    }

    // Total declared options, shown by the stats command
    public int getOptionCount() {
        return this.options.size();
    }

    // How many options a mod forced away from the user's value, which is what makes a config look ignored
    public int getOptionOverrideCount() {
        int count = 0;

        for (Option option : this.options.values()) {
            if (option.isOverridden()) {
                count++;
            }
        }

        return count;
    }

    // Repeats until a pass changes nothing, since disabling one option can break an already-visited dependency; terminates because nothing re-enables, so the enabled count strictly decreases
    private void applyDependencies() {
        //noinspection StatementWithEmptyBody
        while (this.applyDependenciesOnce()) {
        }
    }

    // One sweep; returns whether anything changed so the caller knows to sweep again
    private boolean applyDependenciesOnce() {
        boolean changed = false;

        for (Option optionWithDependency : this.optionsWithDependencies) {
            changed |= optionWithDependency.disableIfDependenciesNotMet(this);
        }

        return changed;
    }

    // Rewrites every key so the file doubles as documentation; a null file is a no-op and IO failure is logged, since losing settings must not stop launch
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

    // Emits the whole file: banner, then each category with its description, dependencies and default
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

            // Untouched options stay commented out so the file keeps saying what the default is instead of freezing today's default into the user's config
            if (option != null && option.isUserDefined()) {
                writer.write(entry.name() + "=" + option.isEnabled() + "\n");
            } else {
                writer.write("#" + entry.name() + "=" + entry.enabledByDefault() + "\n");
            }
        }
    }

    // Greedy word wrap for the description comments; long words are left to overflow rather than broken
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
