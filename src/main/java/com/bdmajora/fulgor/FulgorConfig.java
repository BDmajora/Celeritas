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

// Feature switches for the lighting subsystem. Plain Properties file (like CoartatioConfig) because it
// must be readable from FulgorMixinPlugin during coremod setup, before Forge/Minecraft classes are safe
// to touch; the only outside class referenced here (Launch) is already loaded by that point.
public final class FulgorConfig {
    private static final String FILE_NAME = "impetus-fulgor.cfg";

    private static FulgorConfig instance;

    // Where save() writes; null only if the config directory could not be resolved
    private Path file;

    // Off means vanilla lighting, unmodified; kept separate from the individual switches so "is this
    // bug Fulgor's fault" doesn't require understanding what the other options do
    public boolean enabled;
    // The subsystem's whole point; everything else supports this or fixes a vanilla bug the batching exposes
    public boolean deferredLightUpdates;
    // Alfheim's headline change over Phosphor: bulk edits (worldgen, /fill, explosions) schedule the
    // same position from several neighbours in one tick; without this each gets evaluated separately
    public boolean deduplicateUpdates;
    // Phosphor's BlockStateLightInfo, adapted. Forge's default position-aware getLightOpacity/getLightValue
    // still cost a virtual dispatch (and a redundant world lookup for luminance) even though most blocks'
    // answers don't depend on position. Ignored while Dynamic Lights or Fluidlogged API is installed.
    public boolean cacheBlockLightInfo;
    // Fixes MC-3329: vanilla drops boundary light updates to unloaded neighbours and never revisits them
    public boolean fixChunkBoundaryLighting;
    // Fixes MC-116690: vanilla's emptiness test counts blocks only, so a carved-out section with real
    // light data gets skipped by the chunk packet and the client relights it wrong
    public boolean sendNonTrivialSectionLight;
    // Also fixes MC-80966: vanilla skips the render light-update drain entirely while the chunk builder
    // is busy, so changes can sit unrendered indefinitely under load
    public boolean optimizeRenderLightUpdates;
    // Skips light processing while the game is paused
    public boolean skipUpdatesWhilePaused;
    // Memory bound, not a performance knob: stops a pathological producer from growing a queue unbounded
    public int maxScheduledUpdates;
    // Inherited from Phosphor; always another mod's bug and the engine survives it either way, but loud by default
    public boolean warnOnIllegalThreadAccess;
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

    // Every switch except the two diagnostics and skipUpdatesWhilePaused decides whether a mixin is
    // applied, so changing one takes effect on the next launch (options screen marks those with a restart flag)
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

    // Rewrites the file with every key present so a user who never opened it still discovers the
    // switches; values already set by the user are preserved verbatim
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
