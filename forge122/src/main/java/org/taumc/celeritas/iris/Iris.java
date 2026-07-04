package org.taumc.celeritas.iris;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.taumc.celeritas.iris.config.IrisConfig;
import org.taumc.celeritas.iris.pipeline.IrisPipeline;
import org.taumc.celeritas.iris.pipeline.IrisRenderingPipeline;
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

    /** The active GL pipeline. Built lazily on the render thread (needs a GL context) from {@link #currentPack}. */
    private static IrisPipeline pipeline;
    private static boolean pipelineNeedsInit;

    /** The frame pipeline (gbuffer + composite/final chain). Built lazily at renderWorld HEAD on the render thread. */
    private static IrisRenderingPipeline renderingPipeline;
    /** Set when pipeline construction failed for the current pack, so we don't retry (and re-log) every frame. */
    private static boolean renderingPipelineFailed;

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
            pipelineNeedsInit = true;
            List<String> programs = pack.getProgramSet().listDeclaredPrograms();
            LOGGER.info("Loaded shader pack '{}' with {} program(s): {}", name, programs.size(), programs);
        } catch (Exception e) {
            currentPack = null;
            LOGGER.error("Failed to load shader pack '" + name + "'; shaders disabled", e);
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
    public static synchronized IrisRenderingPipeline beginFrame() {
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
                renderingPipeline = new IrisRenderingPipeline(currentPack);
            } catch (Exception e) {
                renderingPipelineFailed = true;
                LOGGER.error("Failed to build the Iris rendering pipeline; shaders disabled for this pack", e);
            }
        }
        return renderingPipeline;
    }

    /** @return the frame pipeline, or {@code null} when shaders are off. For the mid-frame and end-of-frame hooks. */
    public static IrisRenderingPipeline getRenderingPipeline() {
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
    public static IrisPipeline getPipeline() {
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
        return config == null ? IrisConfig.NO_PACK : config.getShaderPackName();
    }

    /** @return the names of every pack available under {@code shaderpacks/} (for the selection UI). */
    public static List<String> listAvailablePacks() {
        return config == null ? java.util.Collections.emptyList() : config.listShaderpacks();
    }

    /**
     * Selects a shader pack by name (or {@link IrisConfig#NO_PACK} to disable), persists the choice to
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
