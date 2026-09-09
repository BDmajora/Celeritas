package com.bdmajora.impetus.umbra;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.umbra.config.UmbraConfig;
import com.bdmajora.impetus.umbra.pipeline.UmbraPipeline;
import com.bdmajora.impetus.umbra.pipeline.UmbraRenderingPipeline;
import com.bdmajora.impetus.umbra.shaderpack.ShaderPack;
import com.bdmajora.impetus.umbra.shaderpack.ShaderPackLoader;
import com.bdmajora.impetus.umbra.shaderpack.option.values.MutableOptionValues;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Entry point and global state holder for the Umbra shader layer inside Impetus.
 * <p>
 * Phase 1 scope: load the user's selected shader pack from {@code shaderpacks/} (folder or {@code .zip}), parse it, and
 * make the resulting {@link ShaderPack} queryable. There are deliberately <em>no</em> rendering side effects yet — if
 * no pack is selected (or loading fails) the renderer behaves exactly as stock Impetus. That property is what makes
 * Umbra a zero-cost abstraction when disabled.
 * <p>
 * This class is intentionally Minecraft-free: callers pass the game directory. The Forge {@code @Mod} wires it up.
 */
public final class Umbra {
    public static final String MODNAME = "Impetus/Umbra";
    private static final Logger LOGGER = LogManager.getLogger(MODNAME);

    private static UmbraConfig config;
    private static ShaderPack currentPack;

    /** Option values queued by the in-game menu, applied and merged into {@code <pack>.txt} on the next reload. */
    private static final Map<String, String> shaderPackOptionQueue = new HashMap<>();
    /** When set, the next reload discards all changed option values (resets the pack to its defaults). */
    private static boolean resetShaderPackOptions;

    /** The active GL pipeline. Built lazily on the render thread (needs a GL context) from {@link #currentPack}. */
    private static UmbraPipeline pipeline;
    private static boolean pipelineNeedsInit;

    /** The frame pipeline (gbuffer + composite/final chain). Built lazily at renderWorld HEAD on the render thread. */
    private static UmbraRenderingPipeline renderingPipeline;
    /** Set when pipeline construction failed for the current pack, so we don't retry (and re-log) every frame. */
    private static boolean renderingPipelineFailed;

    private Umbra() {
    }

    public static Logger logger() {
        return LOGGER;
    }

    public static UmbraConfig getConfig() {
        return config;
    }

    /**
     * Initializes Umbra against a game directory: loads {@code optionsshaders.txt}, ensures {@code shaderpacks/} exists,
     * and loads the currently-selected pack (if any). Never throws — failures are logged and leave Umbra disabled.
     */
    public static void initialize(Path gameDirectory) {
        config = new UmbraConfig(gameDirectory);
        try {
            config.ensureShaderpacksDirectory();
            config.load();
        } catch (IOException e) {
            LOGGER.error("Failed to load Umbra configuration; shaders disabled", e);
            return;
        }

        if (config.isShaderPackEnabled()) {
            loadCurrentShaderpack();
        }
    }

    /**
     * (Re)loads the pack named by the current {@link UmbraConfig}. Existing state is cleared first. On any failure the
     * pack is left unloaded and the error logged; the game keeps running with stock rendering.
     */
    public static synchronized void loadCurrentShaderpack() {
        unloadShaderpack();

        if (config == null || !config.isShaderPackEnabled()) {
            return;
        }

        Path packPath = config.getSelectedPackPath();
        String name = config.getShaderPackName();
        Path configTxt = config.getShaderpacksDirectory().resolve(name + ".txt");

        // Load the persisted changed option values, layer the queued in-game changes on top, then apply.
        Map<String, String> changedConfigs = readConfigProperties(configTxt);
        changedConfigs.putAll(shaderPackOptionQueue);
        shaderPackOptionQueue.clear();
        if (resetShaderPackOptions) {
            changedConfigs.clear();
        }
        resetShaderPackOptions = false;

        try {
            ShaderPack pack;
            if (Files.isDirectory(packPath)) {
                pack = ShaderPackLoader.loadFromDirectory(packPath, changedConfigs);
            } else if (Files.isRegularFile(packPath) && name.toLowerCase().endsWith(".zip")) {
                pack = ShaderPackLoader.loadFromZip(packPath, changedConfigs);
            } else {
                LOGGER.warn("Selected shader pack '{}' was not found at {}; shaders disabled.", name, packPath);
                return;
            }

            // Persist the effective changed values (only options that differ from pack defaults are stored).
            MutableOptionValues effective = pack.getShaderPackOptions().getOptionValues().mutableCopy();
            Properties toSave = new Properties();
            effective.getBooleanValues().forEach((k, v) -> toSave.setProperty(k, Boolean.toString(v)));
            effective.getStringValues().forEach(toSave::setProperty);
            writeConfigProperties(configTxt, toSave);

            currentPack = pack;
            pipelineNeedsInit = true;
            List<String> programs = pack.getProgramSet().listDeclaredPrograms();
        } catch (Exception e) {
            currentPack = null;
            LOGGER.error("Failed to load shader pack '" + name + "'; shaders disabled", e);
        }
    }

    /**
     * Queues option-value changes from the in-game menu (keyed by option name; {@code true}/{@code false} for booleans
     * or the raw token for string options) and reloads the pack so the changes take effect and are persisted.
     */
    public static synchronized void queueShaderPackOptions(Map<String, String> changes) {
        shaderPackOptionQueue.putAll(changes);
        loadCurrentShaderpack();
    }

    /** Resets all changed option values back to the pack defaults on the next reload, and reloads now. */
    public static synchronized void resetShaderPackOptionsAndReload() {
        resetShaderPackOptions = true;
        loadCurrentShaderpack();
    }

    private static Map<String, String> readConfigProperties(Path path) {
        Map<String, String> result = new HashMap<>();
        if (!Files.exists(path)) {
            return result;
        }
        Properties properties = new Properties();
        // NB: OptiFine specifies these config files as ISO-8859-1, but Properties.load defaults to that for byte
        //     streams, so no special handling is needed.
        try (InputStream is = Files.newInputStream(path)) {
            properties.load(is);
        } catch (IOException e) {
            LOGGER.warn("Failed to read shader option config {}; using defaults", path, e);
            return result;
        }
        properties.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
        return result;
    }

    private static void writeConfigProperties(Path path, Properties properties) {
        try (OutputStream os = Files.newOutputStream(path)) {
            properties.store(os, "This file stores overrides for the shader pack's default options.");
        } catch (IOException e) {
            LOGGER.warn("Failed to write shader option config {}", path, e);
        }
    }

    /**
     * Render-thread hook: builds (or rebuilds) the GL pipeline for the current pack the first frame after a pack
     * change. Cheap no-op when there is nothing to do. Must be called with a current GL context (e.g. from a
     * {@code RenderTickEvent}). Never throws — a compile failure disables shaders and logs.
     */
    public static synchronized void updatePipeline() {
        if (!pipelineNeedsInit) {
            return;
        }
        // When the pack was switched off entirely there will be no renderWorld-driven rebuild, so tear down here
        // (this runs on the render thread with a GL context). When a pack IS active, leave the flag set for
        // beginFrame() so teardown and rebuild happen together at renderWorld HEAD.
        if (currentPack == null) {
            pipelineNeedsInit = false;
            destroyPipelines();
        }
    }

    /**
     * Render-thread hook for {@code renderWorld} HEAD: (re)builds the frame pipeline the first frame after a pack
     * change and returns it, or {@code null} when shaders are off or the pack failed to build. Never throws — a
     * broken pack logs once and leaves rendering stock.
     */
    public static synchronized UmbraRenderingPipeline beginFrame() {
        if (pipelineNeedsInit) {
            pipelineNeedsInit = false;
            destroyPipelines();
            renderingPipelineFailed = false;
        }
        if (currentPack == null || renderingPipelineFailed) {
            return null;
        }
        if (renderingPipeline == null) {
            try {
                renderingPipeline = new UmbraRenderingPipeline(currentPack);
            } catch (Exception e) {
                renderingPipelineFailed = true;
                LOGGER.error("Failed to build the Umbra rendering pipeline; shaders disabled for this pack", e);
            }
        }
        return renderingPipeline;
    }

    /** @return the frame pipeline, or {@code null} when shaders are off. For the mid-frame and end-of-frame hooks. */
    public static UmbraRenderingPipeline getRenderingPipeline() {
        return renderingPipeline;
    }

    private static void destroyPipelines() {
        if (pipeline != null) {
            pipeline.destroy();
            pipeline = null;
        }
        if (renderingPipeline != null) {
            renderingPipeline.destroy();
            renderingPipeline = null;
        }
    }

    /** @return the active GL pipeline, or {@code null} when shaders are disabled or not yet built this frame. */
    public static UmbraPipeline getPipeline() {
        return pipeline;
    }

    /**
     * Drops the active pack. Phase 1 simply releases the parsed model; GL resource teardown (the hot-swap cleanup
     * protocol) is wired in once the rendering phases exist.
     */
    public static synchronized void unloadShaderpack() {
        currentPack = null;
        // GL teardown must run on the render thread; flag a rebuild so updatePipeline() disposes the pipeline there.
        pipelineNeedsInit = true;
    }

    /** @return {@code true} when a shader pack is parsed and active. */
    public static boolean isShaderPackInUse() {
        return currentPack != null;
    }

    /** @return the active shader pack, or {@code null} when shaders are disabled. */
    public static ShaderPack getCurrentPack() {
        return currentPack;
    }

    /** @return the name of the currently selected pack, or the "off" sentinel when none is selected. */
    public static String getSelectedPackName() {
        return config == null ? UmbraConfig.NO_PACK : config.getShaderPackName();
    }

    /** @return the names of every pack available under {@code shaderpacks/} (for the selection UI). */
    public static List<String> listAvailablePacks() {
        return config == null ? java.util.Collections.emptyList() : config.listShaderpacks();
    }

    /**
     * Selects a shader pack by name (or {@link UmbraConfig#NO_PACK} to disable), persists the choice to
     * {@code optionsshaders.txt}, and re-parses it. The GL pipeline is (re)built on the next render frame by
     * {@link #updatePipeline()}. Safe to call from the client/GUI thread — no GL work happens here.
     */
    public static synchronized void setShaderpackAndReload(String name) {
        if (config == null) {
            return;
        }
        config.setShaderPackName(name);
        try {
            config.save();
        } catch (IOException e) {
            LOGGER.error("Failed to save shader selection to optionsshaders.txt", e);
        }
        loadCurrentShaderpack();
    }
}
