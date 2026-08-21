package com.bdmajora.fulgor;

import net.minecraft.launchwrapper.Launch;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Feature switches for the lighting subsystem.
 *
 * <p>A plain {@link Properties} file for the same reason {@code CoartatioConfig} is one: it has to be
 * readable from {@link com.bdmajora.fulgor.mixin.FulgorMixinPlugin}, which runs during coremod setup,
 * long before Forge or Minecraft classes are safe to touch. The only outside class referenced here is
 * {@link Launch}, which is already loaded by that point.
 */
public final class FulgorConfig {
    private static final String FILE_NAME = "impetus-fulgor.cfg";

    private static FulgorConfig instance;

    /** Where {@link #save()} writes. Null only if the config directory could not be resolved. */
    private Path file;

    /**
     * Master switch for the whole subsystem.
     *
     * <p>Off means vanilla lighting, unmodified. Kept separate from the individual switches because
     * "is this bug Fulgor's fault" is the first question anyone asks, and answering it should not
     * require understanding what the other options do.
     */
    public boolean enabled;
    /**
     * Replaces vanilla's immediate, recursive light propagation with the batched engine.
     *
     * <p>The subsystem's whole point. Everything else either supports this or fixes a vanilla bug the
     * batching exposes.
     */
    public boolean deferredLightUpdates;
    /**
     * Collapses repeated updates for the same position into one.
     *
     * <p>Alfheim's headline change over Phosphor. Bulk edits — world generation, {@code /fill},
     * explosions, quarries — schedule the same position from several neighbours in the same tick;
     * without this each one is evaluated separately.
     */
    public boolean deduplicateUpdates;
    /**
     * Caches, per block, whether its light values can vary with position.
     *
     * <p>Phosphor's {@code BlockStateLightInfo}, adapted. The overwhelming majority of blocks answer
     * {@code getLightOpacity}/{@code getLightValue} from the state alone, but Forge's default
     * implementation still costs a virtual dispatch and — for luminance — a redundant world lookup on
     * every neighbour of every update.
     *
     * <p>Ignored while Dynamic Lights or Fluidlogged API is installed; see
     * {@link Fulgor#requiresPositionAwareLight()}.
     */
    public boolean cacheBlockLightInfo;
    /**
     * Propagates skylight into a chunk's neighbours across the chunk boundary.
     *
     * <p>Fixes MC-3329 and its relatives. Vanilla drops boundary updates when the neighbour is not
     * loaded and never revisits them, which is where world-generation light seams come from.
     */
    public boolean fixChunkBoundaryLighting;
    /**
     * Sends chunk sections whose lighting is non-trivial even when they hold no blocks.
     *
     * <p>Fixes MC-116690. Vanilla's emptiness test counts blocks only, so a fully-carved-out section
     * with real light data is skipped by the chunk packet and the client relights it to whatever the
     * fallback rule produces.
     */
    public boolean sendNonTrivialSectionLight;
    /**
     * Drains the renderer's light-update queue through a deduplicated long queue.
     *
     * <p>Also fixes MC-80966: vanilla skips the drain entirely whenever the chunk builder is busy, so
     * light changes can sit unrendered indefinitely under load.
     */
    public boolean optimizeRenderLightUpdates;
    /** Skips light processing while the game is paused. */
    public boolean skipUpdatesWhilePaused;
    /**
     * Number of queued updates for one light type after which the engine processes them immediately
     * rather than waiting for a query.
     *
     * <p>A memory bound, not a performance knob. Deferral is what makes the engine fast; this only
     * stops a pathological producer from growing the queue without limit.
     */
    public int maxScheduledUpdates;
    /**
     * Warns when a thread that does not own a world modifies its lighting.
     *
     * <p>Inherited from Phosphor. It is always another mod's bug, the engine survives it either way,
     * and the warning is loud — so it is on by default but switchable.
     */
    public boolean warnOnIllegalThreadAccess;
    /** Prints engine statistics to the log when the player leaves a world. */
    public boolean logStatistics;
    /** Adds a Fulgor line to the F3 debug overlay. */
    public boolean showDebugOverlay;

    private FulgorConfig(Properties props) {
        this.enabled = bool(props, "enabled", true);
        this.deferredLightUpdates = bool(props, "deferredLightUpdates", true);
        this.deduplicateUpdates = bool(props, "deduplicateUpdates", true);
        this.cacheBlockLightInfo = bool(props, "cacheBlockLightInfo", true);
        this.fixChunkBoundaryLighting = bool(props, "fixChunkBoundaryLighting", true);
        this.sendNonTrivialSectionLight = bool(props, "sendNonTrivialSectionLight", true);
        this.optimizeRenderLightUpdates = bool(props, "optimizeRenderLightUpdates", true);
        this.skipUpdatesWhilePaused = bool(props, "skipUpdatesWhilePaused", true);
        this.maxScheduledUpdates = integer(props, "maxScheduledUpdates", 1 << 22, 1 << 12, Integer.MAX_VALUE);
        this.warnOnIllegalThreadAccess = bool(props, "warnOnIllegalThreadAccess", true);
        this.logStatistics = bool(props, "logStatistics", false);
        this.showDebugOverlay = bool(props, "showDebugOverlay", false);
    }

    public static FulgorConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static FulgorConfig load() {
        Path file = configDirectory().resolve(FILE_NAME);

        Properties props = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                Fulgor.LOGGER.error("Could not read {}, falling back to defaults", file, e);
            }
        }

        FulgorConfig config = new FulgorConfig(props);
        config.file = file;
        config.save();
        return config;
    }

    /**
     * Persists the current values.
     *
     * <p>Every switch except the two diagnostics and {@link #skipUpdatesWhilePaused} decides whether a
     * mixin is applied, so changing one takes effect on the next launch. The options screen marks
     * those with a restart flag.
     */
    public void save() {
        if (this.file != null) {
            writeBack(this.file);
        }
    }

    private static Path configDirectory() {
        File home = Launch.minecraftHome;
        Path dir = (home == null ? Paths.get(".") : home.toPath()).resolve("config");

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Fulgor.LOGGER.warn("Could not create {}, configuration will not persist", dir, e);
        }

        return dir;
    }

    /**
     * Rewrites the file with every key present, so a user who has never opened it still discovers the
     * switches. Values already set by the user are preserved verbatim.
     */
    private void writeBack(Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("enabled", Boolean.toString(this.enabled));
        values.put("deferredLightUpdates", Boolean.toString(this.deferredLightUpdates));
        values.put("deduplicateUpdates", Boolean.toString(this.deduplicateUpdates));
        values.put("cacheBlockLightInfo", Boolean.toString(this.cacheBlockLightInfo));
        values.put("fixChunkBoundaryLighting", Boolean.toString(this.fixChunkBoundaryLighting));
        values.put("sendNonTrivialSectionLight", Boolean.toString(this.sendNonTrivialSectionLight));
        values.put("optimizeRenderLightUpdates", Boolean.toString(this.optimizeRenderLightUpdates));
        values.put("skipUpdatesWhilePaused", Boolean.toString(this.skipUpdatesWhilePaused));
        values.put("maxScheduledUpdates", Integer.toString(this.maxScheduledUpdates));
        values.put("warnOnIllegalThreadAccess", Boolean.toString(this.warnOnIllegalThreadAccess));
        values.put("logStatistics", Boolean.toString(this.logStatistics));
        values.put("showDebugOverlay", Boolean.toString(this.showDebugOverlay));

        Properties out = new Properties();
        out.putAll(values);

        try (OutputStream stream = Files.newOutputStream(file)) {
            out.store(stream, "Impetus / Fulgor lighting subsystem. Delete a line to restore its default.");
        } catch (IOException e) {
            Fulgor.LOGGER.warn("Could not write {}", file, e);
        }
    }

    private static boolean bool(Properties props, String key, boolean fallback) {
        String value = props.getProperty(key);
        if (value == null) {
            return fallback;
        }
        value = value.trim();
        return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)
                ? Boolean.parseBoolean(value)
                : fallback;
    }

    private static int integer(Properties props, String key, int fallback, int min, int max) {
        String value = props.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed < min || parsed > max ? fallback : parsed;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
