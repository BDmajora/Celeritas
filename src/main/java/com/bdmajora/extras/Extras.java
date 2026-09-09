package com.bdmajora.extras;

import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

// Sodium Extra's option set, ported to Impetus on 1.12.2
// Owns no rendering itself, so unlike Fulgor/Coartatio none of its mixins need coremod gating —
// they all read options() live, so switches take effect immediately
public final class Extras {
    public static final Logger LOGGER = LogManager.getLogger("Impetus/Extras");

    private static final String FILE_NAME = "impetus-extras.cfg";

    private static volatile ExtrasConfig config;

    private Extras() {
    }

    // Called from mixin bodies on render/client threads, so first-call safety matters;
    // ImpetusVintage warms this during construction well before that can happen
    public static ExtrasConfig options() {
        ExtrasConfig loaded = config;
        if (loaded == null) {
            synchronized (Extras.class) {
                loaded = config;
                if (loaded == null) {
                    loaded = ExtrasConfig.load(configFile());
                    config = loaded;
                }
            }
        }
        return loaded;
    }

    // Loads the config now rather than on the first mixin that asks for it
    public static void initialize() {
        options();
    }

    // Persists the current options; safe to call before initialize()
    public static void save() {
        options().writeChanges();
    }

    private static File configFile() {
        File home = Launch.minecraftHome;
        File directory = new File(home == null ? new File(".") : home, "config");

        if (!directory.isDirectory() && !directory.mkdirs()) {
            LOGGER.warn("Could not create {}, Extras settings will not persist", directory);
        }

        return new File(directory, FILE_NAME);
    }
}
