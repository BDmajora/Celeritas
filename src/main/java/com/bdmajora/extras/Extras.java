package com.bdmajora.extras;

import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * The Extras subsystem: Sodium Extra's option set, ported to Impetus on 1.12.2.
 *
 * <p>Everything it adds is a switch over rendering the game already does — animations, particles,
 * sky detail, fog, clouds, entity and block-entity passes, plus the HUD overlay and toast filters.
 * It owns no rendering of its own, which is why (unlike {@code Fulgor} or {@code Coartatio}) none of
 * its mixins are gated at coremod time: they all read {@link #options()} at call time, so every
 * switch takes effect the moment it changes.
 *
 * <p>Sources: <a href="https://github.com/FlashyReese/sodium-extra-fabric">Sodium Extra</a> for the
 * option set and semantics, Celeritas Extra for most of the 1.12.2 injection points, and OptiFine
 * 1.12.2 for the finer animation, particle and detail switches Sodium Extra does not have.
 */
public final class Extras {
    public static final Logger LOGGER = LogManager.getLogger("Impetus/Extras");

    private static final String FILE_NAME = "impetus-extras.cfg";

    private static volatile ExtrasConfig config;

    private Extras() {
    }

    /**
     * The live options, loading them on first use.
     *
     * <p>Called from mixin bodies on the render and client threads, so the first call has to be safe
     * from wherever it happens to land. {@link ImpetusVintage} warms it during construction, which
     * in practice is always well before any of those bodies run.
     */
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

    /** Loads the config now rather than on the first mixin that asks for it. */
    public static void initialize() {
        options();
        LOGGER.info("Extras options loaded");
    }

    /** Persists the current options. Safe to call before {@link #initialize()}. */
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
