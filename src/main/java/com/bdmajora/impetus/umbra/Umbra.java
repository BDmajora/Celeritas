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

// Entry point and global state holder for the Umbra shader layer
// Owns three things that have different lifetimes and must not be conflated: the parsed ShaderPack, the compiled
// UmbraPipeline, and the per-frame UmbraRenderingPipeline
// The parse happens off the render thread, from the game directory; both pipelines are built lazily on the render
// thread because they need a live GL context
// Nothing here throws. Every failure path leaves shaders off and the renderer behaving exactly as it does with no
// pack selected, which is what keeps this a zero-cost layer when disabled
// Deliberately free of Minecraft classes at the entry point — callers pass the game directory in, and the Forge
// @Mod wires it up
public final class Umbra {
    public static final String MODNAME = "Impetus/Umbra";
    private static final Logger LOGGER = LogManager.getLogger(MODNAME);

    private static UmbraConfig config;
    private static ShaderPack currentPack;

    // Option values queued by the in-game menu, applied and merged into <pack>.txt on the next reload
    // Queued rather than applied immediately because changing an option means recompiling the pack, which can only
    // happen on the render thread
    private static final Map<String, String> shaderPackOptionQueue = new HashMap<>();
    // When set, the next reload discards every changed option value and takes the pack's own defaults
    private static boolean resetShaderPackOptions;

    // The compiled programs for the current pack. Built lazily on the render thread, since compiling needs a GL
    // context
    private static UmbraPipeline pipeline;
    private static boolean pipelineNeedsInit;

    // The frame pipeline: render targets, the gbuffer, and the composite/final chain
    // Built at renderWorld HEAD rather than alongside the compile, because it sizes its targets from the current
    // framebuffer dimensions
    private static UmbraRenderingPipeline renderingPipeline;
    // Latched when construction failed for the current pack, so the attempt is not repeated — and re-logged — on
    // every single frame for the rest of the session
    private static boolean renderingPipelineFailed;

    private Umbra() {
    }

    public static Logger logger() {
        return LOGGER;
    }

    public static UmbraConfig getConfig() {
        return config;
    }

    // Brings Umbra up against a game directory: loads optionsshaders.txt, creates shaderpacks/ if absent, and
    // parses whichever pack is currently selected
    // Never throws. A failure here logs and leaves Umbra disabled, because a broken pack must not stop the game
    // from starting
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

    // Loads, or reloads, the pack the config names
    // Existing state is cleared FIRST, so a failed load cannot leave half of the previous pack live alongside none
    // of the new one
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

    // Queues option changes from the in-game menu and reloads so they take effect and get persisted
    // Keyed by option name; the value is "true"/"false" for a boolean option or the raw token for a string one
    public static synchronized void queueShaderPackOptions(Map<String, String> changes) {
        shaderPackOptionQueue.putAll(changes);
        loadCurrentShaderpack();
    }

    // Resets every changed option back to the pack's own defaults and reloads immediately
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

    // Render-thread hook: builds or rebuilds the compiled pipeline the first frame after a pack change
    // A cheap no-op when nothing changed, so it is safe to call unconditionally each frame
    // Requires a current GL context — a RenderTickEvent is the intended caller. A compile failure logs and leaves
    // shaders off rather than throwing
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

    // Render-thread hook for renderWorld HEAD: builds the frame pipeline on the first frame after a pack change,
    // then returns it
    // Null when shaders are off OR the pack failed to build, and the caller treats both the same way — render
    // vanilla
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

    // The frame pipeline for the mid-frame and end-of-frame hooks, or null when shaders are off
    // Unlike beginFrame this never BUILDS anything, so it is safe from any hook regardless of ordering
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

    // The compiled pipeline, or null when shaders are disabled or it has not been built yet this frame
    public static UmbraPipeline getPipeline() {
        return pipeline;
    }

    // Drops the active pack and everything built from it
    public static synchronized void unloadShaderpack() {
        currentPack = null;
        // GL teardown must run on the render thread; flag a rebuild so updatePipeline() disposes the pipeline there.
        pipelineNeedsInit = true;
    }

    // Whether a pack is parsed and active. This is what IrisApi reports to other mods, so it means "shaders are
    // running", not merely "a pack is selected"
    public static boolean isShaderPackInUse() {
        return currentPack != null;
    }

    // The parsed pack, or null when shaders are disabled
    public static ShaderPack getCurrentPack() {
        return currentPack;
    }

    // The selected pack's name, or the "off" sentinel when none is — selection is config state and exists even
    // when nothing loaded successfully
    public static String getSelectedPackName() {
        return config == null ? UmbraConfig.NO_PACK : config.getShaderPackName();
    }

    // Every pack found under shaderpacks/, for the selection screen
    public static List<String> listAvailablePacks() {
        return config == null ? java.util.Collections.emptyList() : config.listShaderpacks();
    }

    // Selects a pack by name, or the NO_PACK sentinel to disable, persists the choice to optionsshaders.txt, and
    // re-parses
    // Deliberately does NO GL work, which is what makes it safe to call straight from the GUI thread — the pipeline
    // is rebuilt on the next render frame by updatePipeline
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
