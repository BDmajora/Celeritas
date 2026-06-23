package org.taumc.celeritas.iris;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.taumc.celeritas.iris.config.IrisConfig;
import org.taumc.celeritas.iris.shaderpack.ShaderPack;
import org.taumc.celeritas.iris.shaderpack.ShaderPackLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point and global state holder for the Iris shader layer inside Celeritas.
 * <p>
 * Phase 1 scope: load the user's selected shader pack from {@code shaderpacks/} (folder or {@code .zip}), parse it, and
 * make the resulting {@link ShaderPack} queryable. There are deliberately <em>no</em> rendering side effects yet — if
 * no pack is selected (or loading fails) the renderer behaves exactly as stock Celeritas. That property is what makes
 * Iris a zero-cost abstraction when disabled.
 * <p>
 * This class is intentionally Minecraft-free: callers pass the game directory. The Forge {@code @Mod} wires it up.
 */
public final class Iris {
    public static final String MODNAME = "Celeritas/Iris";
    private static final Logger LOGGER = LogManager.getLogger(MODNAME);

    private static IrisConfig config;
    private static ShaderPack currentPack;

    private Iris() {
    }

    public static Logger logger() {
        return LOGGER;
    }

    public static IrisConfig getConfig() {
        return config;
    }

    /**
     * Initializes Iris against a game directory: loads {@code optionsshaders.txt}, ensures {@code shaderpacks/} exists,
     * and loads the currently-selected pack (if any). Never throws — failures are logged and leave Iris disabled.
     */
    public static void initialize(Path gameDirectory) {
        config = new IrisConfig(gameDirectory);
        try {
            config.ensureShaderpacksDirectory();
            config.load();
        } catch (IOException e) {
            LOGGER.error("Failed to load Iris configuration; shaders disabled", e);
            return;
        }

        if (config.isShaderPackEnabled()) {
            loadCurrentShaderpack();
        } else {
            LOGGER.info("No shader pack selected; Celeritas rendering unchanged.");
        }
    }

    /**
     * (Re)loads the pack named by the current {@link IrisConfig}. Existing state is cleared first. On any failure the
     * pack is left unloaded and the error logged; the game keeps running with stock rendering.
     */
    public static synchronized void loadCurrentShaderpack() {
        unloadShaderpack();

        if (config == null || !config.isShaderPackEnabled()) {
            return;
        }

        Path packPath = config.getSelectedPackPath();
        String name = config.getShaderPackName();

        try {
            ShaderPack pack;
            if (Files.isDirectory(packPath)) {
                pack = ShaderPackLoader.loadFromDirectory(packPath);
            } else if (Files.isRegularFile(packPath) && name.toLowerCase().endsWith(".zip")) {
                pack = ShaderPackLoader.loadFromZip(packPath);
            } else {
                LOGGER.warn("Selected shader pack '{}' was not found at {}; shaders disabled.", name, packPath);
                return;
            }

            currentPack = pack;
            List<String> programs = pack.getProgramSet().listDeclaredPrograms();
            LOGGER.info("Loaded shader pack '{}' with {} program(s): {}", name, programs.size(), programs);
        } catch (Exception e) {
            currentPack = null;
            LOGGER.error("Failed to load shader pack '" + name + "'; shaders disabled", e);
        }
    }

    /**
     * Drops the active pack. Phase 1 simply releases the parsed model; GL resource teardown (the hot-swap cleanup
     * protocol) is wired in once the rendering phases exist.
     */
    public static synchronized void unloadShaderpack() {
        currentPack = null;
    }

    /** @return {@code true} when a shader pack is parsed and active. */
    public static boolean isShaderPackInUse() {
        return currentPack != null;
    }

    /** @return the active shader pack, or {@code null} when shaders are disabled. */
    public static ShaderPack getCurrentPack() {
        return currentPack;
    }
}
