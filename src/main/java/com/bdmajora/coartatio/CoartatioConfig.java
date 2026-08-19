package com.bdmajora.coartatio;

import net.minecraft.launchwrapper.Launch;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Feature switches for the memory subsystem.
 *
 * <p>This is deliberately a plain {@link Properties} file rather than Forge's {@code Configuration}
 * or Impetus' own {@code ImpetusGameOptions}: it has to be readable from
 * {@link com.bdmajora.coartatio.mixin.CoartatioMixinPlugin}, which runs during coremod
 * setup, long before Forge or Minecraft classes are safe to touch. The only outside class referenced
 * here is {@link Launch}, which is already loaded by that point.
 */
public final class CoartatioConfig {
    private static final String FILE_NAME = "impetus-coartatio.cfg";

    /**
     * BiblioCraft derives block states in a way that assumes the vanilla table exists. Inherited from
     * FoamFix, which hit the same wall.
     */
    private static final String DEFAULT_BLOCK_STATE_BLACKLIST = "jds.bibliocraft";

    private static CoartatioConfig instance;

    /** Interns the domain and path strings of every {@code ResourceLocation}. */
    public final boolean deduplicateResourceLocations;
    /** Interns the {@code variant} string of every {@code ModelResourceLocation}. */
    public final boolean deduplicateModelVariants;
    /** Replaces {@code NBTTagCompound}'s {@code HashMap} with a compact array/hash hybrid. */
    public final boolean compactNbtBackingMap;
    /** Interns NBT keys through a shared string pool. Requires {@link #compactNbtBackingMap}. */
    public final boolean internNbtKeys;
    /** Entry count at which an NBT compound switches from array storage to hash storage. */
    public final int nbtArrayMapThreshold;
    /** Pools {@code BakedQuad.vertexData} arrays so identical geometry shares one array. */
    public final boolean poolQuadVertexData;
    /** Replaces the model classes' growable collections with exact-sized immutable ones. */
    public final boolean compactBakedModels;
    /** Flattens and interns the predicates produced by multipart blockstate conditions. */
    public final boolean canonicalizeMultipartConditions;
    /**
     * Replaces every block state's property-value table with a packed {@code int} index into one
     * shared array per block.
     *
     * <p>Off by default. This is the largest saving Coartatio can make and also the only feature that
     * changes the identity of objects the entire game holds references to, so it wants a deliberate
     * opt-in and a session of testing before it becomes the default.
     */
    public final boolean optimizeBlockStates;
    /** Block implementation class prefixes that keep vanilla states regardless of the above. */
    public final String[] blockStateBlacklist;
    /** Upper bound on any single deduplication pool, after which it stops accepting new entries. */
    public final int poolSizeLimit;
    /** Prints pool statistics to the log after every resource reload. */
    public final boolean logStatistics;
    /** Adds a Coartatio line to the F3 debug overlay. */
    public final boolean showDebugOverlay;

    private CoartatioConfig(Properties props) {
        this.deduplicateResourceLocations = bool(props, "deduplicateResourceLocations", true);
        this.deduplicateModelVariants = bool(props, "deduplicateModelVariants", true);
        this.compactNbtBackingMap = bool(props, "compactNbtBackingMap", true);
        this.internNbtKeys = bool(props, "internNbtKeys", true);
        this.nbtArrayMapThreshold = integer(props, "nbtArrayMapThreshold", 12, 0, 1024);
        this.poolQuadVertexData = bool(props, "poolQuadVertexData", true);
        this.compactBakedModels = bool(props, "compactBakedModels", true);
        this.canonicalizeMultipartConditions = bool(props, "canonicalizeMultipartConditions", true);
        this.optimizeBlockStates = bool(props, "optimizeBlockStates", false);
        this.blockStateBlacklist = list(props, "blockStateBlacklist", DEFAULT_BLOCK_STATE_BLACKLIST);
        this.poolSizeLimit = integer(props, "poolSizeLimit", 262144, 1024, Integer.MAX_VALUE);
        this.logStatistics = bool(props, "logStatistics", true);
        this.showDebugOverlay = bool(props, "showDebugOverlay", true);
    }

    public static CoartatioConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static CoartatioConfig load() {
        Path file = configDirectory().resolve(FILE_NAME);

        Properties props = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                Coartatio.LOGGER.error("Could not read {}, falling back to defaults", file, e);
            }
        }

        CoartatioConfig config = new CoartatioConfig(props);
        config.writeBack(file);
        return config;
    }

    private static Path configDirectory() {
        File home = Launch.minecraftHome;
        Path dir = (home == null ? Paths.get(".") : home.toPath()).resolve("config");

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Coartatio.LOGGER.warn("Could not create {}, configuration will not persist", dir, e);
        }

        return dir;
    }

    /**
     * Rewrites the file with every key present and commented, so a user who has never opened it
     * still discovers the switches. Values already set by the user are preserved verbatim.
     */
    private void writeBack(Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("deduplicateResourceLocations", Boolean.toString(this.deduplicateResourceLocations));
        values.put("deduplicateModelVariants", Boolean.toString(this.deduplicateModelVariants));
        values.put("compactNbtBackingMap", Boolean.toString(this.compactNbtBackingMap));
        values.put("internNbtKeys", Boolean.toString(this.internNbtKeys));
        values.put("nbtArrayMapThreshold", Integer.toString(this.nbtArrayMapThreshold));
        values.put("poolQuadVertexData", Boolean.toString(this.poolQuadVertexData));
        values.put("compactBakedModels", Boolean.toString(this.compactBakedModels));
        values.put("canonicalizeMultipartConditions", Boolean.toString(this.canonicalizeMultipartConditions));
        values.put("optimizeBlockStates", Boolean.toString(this.optimizeBlockStates));
        values.put("blockStateBlacklist", String.join(",", this.blockStateBlacklist));
        values.put("poolSizeLimit", Integer.toString(this.poolSizeLimit));
        values.put("logStatistics", Boolean.toString(this.logStatistics));
        values.put("showDebugOverlay", Boolean.toString(this.showDebugOverlay));

        Properties out = new Properties();
        out.putAll(values);

        try (OutputStream stream = Files.newOutputStream(file)) {
            out.store(stream, "Impetus / Coartatio memory subsystem. Delete a line to restore its default.");
        } catch (IOException e) {
            Coartatio.LOGGER.warn("Could not write {}", file, e);
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

    /** Comma-separated list; blank entries are dropped so a trailing comma is harmless. */
    private static String[] list(Properties props, String key, String fallback) {
        String value = props.getProperty(key);

        if (value == null) {
            value = fallback;
        }

        List<String> entries = new ArrayList<>();
        for (String entry : value.split(",")) {
            entry = entry.trim();

            if (!entry.isEmpty()) {
                entries.add(entry);
            }
        }

        return entries.toArray(new String[0]);
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
