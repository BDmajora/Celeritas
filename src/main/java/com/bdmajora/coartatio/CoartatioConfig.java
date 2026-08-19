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

    /** Where {@link #save()} writes. Null only if the config directory could not be resolved. */
    private Path file;

    /** Interns the domain and path strings of every {@code ResourceLocation}. */
    public boolean deduplicateResourceLocations;
    /** Interns the {@code variant} string of every {@code ModelResourceLocation}. */
    public boolean deduplicateModelVariants;
    /** Replaces {@code NBTTagCompound}'s {@code HashMap} with a compact array/hash hybrid. */
    public boolean compactNbtBackingMap;
    /** Interns NBT keys through a shared string pool. Requires {@link #compactNbtBackingMap}. */
    public boolean internNbtKeys;
    /** Entry count at which an NBT compound switches from array storage to hash storage. */
    public int nbtArrayMapThreshold;
    /** Pools {@code BakedQuad.vertexData} arrays so identical geometry shares one array. */
    public boolean poolQuadVertexData;
    /** Replaces the model classes' growable collections with exact-sized immutable ones. */
    public boolean compactBakedModels;
    /** Flattens and interns the predicates produced by multipart blockstate conditions. */
    public boolean canonicalizeMultipartConditions;
    /**
     * Replaces every block state's property-value table with a packed {@code int} index into one
     * shared array per block.
     *
     * <p>The largest single saving Coartatio makes. Falls back to vanilla states per block whenever
     * the mapper declines one — blacklisted, too many states, or an {@code IProperty} it cannot
     * index — so a problem block costs a missed optimisation rather than a crash.
     */
    public boolean optimizeBlockStates;
    /** Block implementation class prefixes that keep vanilla states regardless of the above. */
    public String[] blockStateBlacklist;
    /**
     * Replaces each block state's property {@code ImmutableMap} with a compact one that shares its
     * key array across the block.
     *
     * <p>Requires {@link #optimizeBlockStates}, and a JVM that lets us define a class into Guava's
     * package. Degrades silently to Guava's own map when either is missing.
     */
    public boolean compactStateProperties;
    /** Swaps the model graph's unordered hash maps for fastutil equivalents. */
    public boolean compactModelGraph;
    /** Strips a loaded chunk's NBT down to the tags entity loading still reads. */
    public boolean stripChunkNbt;
    /** Drops chunk sections holding no blocks and no light that could not be recomputed. */
    public boolean dropEmptyChunkSections;
    /** Swaps {@code LaunchClassLoader}'s resource cache for one the GC can reclaim. */
    public boolean weakenClassLoaderCache;
    /** Releases the pixel data of static sprites once the atlas is on the GPU. */
    public boolean releaseSpriteData;
    /** Shares the camera transforms and override lists that every baked model carries. */
    public boolean deduplicateModelTransforms;
    /** Frees the model loader's unbaked models and load errors after baking. */
    public boolean releaseBakeState;
    /** Frees the play-scoped string pools when the player leaves a world or server. */
    public boolean clearPoolsOnWorldLeave;
    /** Defers building the creative search index until something actually searches. */
    public boolean lazySearchTrees;
    /** Compacts registry, entity data and chunk entity-lookup collections. */
    public boolean compactRuntimeCollections;
    /** Upper bound on any single deduplication pool, after which it stops accepting new entries. */
    public int poolSizeLimit;
    /** Prints pool statistics to the log after every resource reload. */
    public boolean logStatistics;
    /** Adds a Coartatio line to the F3 debug overlay. */
    public boolean showDebugOverlay;

    private CoartatioConfig(Properties props) {
        this.deduplicateResourceLocations = bool(props, "deduplicateResourceLocations", true);
        this.deduplicateModelVariants = bool(props, "deduplicateModelVariants", true);
        this.compactNbtBackingMap = bool(props, "compactNbtBackingMap", true);
        this.internNbtKeys = bool(props, "internNbtKeys", true);
        this.nbtArrayMapThreshold = integer(props, "nbtArrayMapThreshold", 12, 0, 1024);
        this.poolQuadVertexData = bool(props, "poolQuadVertexData", true);
        this.compactBakedModels = bool(props, "compactBakedModels", true);
        this.canonicalizeMultipartConditions = bool(props, "canonicalizeMultipartConditions", true);
        this.optimizeBlockStates = bool(props, "optimizeBlockStates", true);
        this.blockStateBlacklist = list(props, "blockStateBlacklist", DEFAULT_BLOCK_STATE_BLACKLIST);
        this.compactStateProperties = bool(props, "compactStateProperties", true);
        this.compactModelGraph = bool(props, "compactModelGraph", true);
        this.stripChunkNbt = bool(props, "stripChunkNbt", true);
        this.dropEmptyChunkSections = bool(props, "dropEmptyChunkSections", true);
        this.weakenClassLoaderCache = bool(props, "weakenClassLoaderCache", true);
        this.releaseSpriteData = bool(props, "releaseSpriteData", true);
        this.deduplicateModelTransforms = bool(props, "deduplicateModelTransforms", true);
        this.releaseBakeState = bool(props, "releaseBakeState", true);
        this.compactRuntimeCollections = bool(props, "compactRuntimeCollections", true);
        this.clearPoolsOnWorldLeave = bool(props, "clearPoolsOnWorldLeave", true);
        this.lazySearchTrees = bool(props, "lazySearchTrees", true);
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
        config.file = file;
        config.save();
        return config;
    }

    /**
     * Persists the current values.
     *
     * <p>Most switches are read once, when {@code CoartatioMixinPlugin} decides which mixins to
     * apply, so changing them here takes effect on the next launch rather than immediately. The
     * options screen marks those with a restart flag; the handful that are read live
     * ({@link #logStatistics}, {@link #showDebugOverlay}, and the NBT map settings, which apply to
     * compounds created from now on) take effect straight away.
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
        values.put("compactStateProperties", Boolean.toString(this.compactStateProperties));
        values.put("compactModelGraph", Boolean.toString(this.compactModelGraph));
        values.put("stripChunkNbt", Boolean.toString(this.stripChunkNbt));
        values.put("dropEmptyChunkSections", Boolean.toString(this.dropEmptyChunkSections));
        values.put("weakenClassLoaderCache", Boolean.toString(this.weakenClassLoaderCache));
        values.put("releaseSpriteData", Boolean.toString(this.releaseSpriteData));
        values.put("deduplicateModelTransforms", Boolean.toString(this.deduplicateModelTransforms));
        values.put("releaseBakeState", Boolean.toString(this.releaseBakeState));
        values.put("compactRuntimeCollections", Boolean.toString(this.compactRuntimeCollections));
        values.put("clearPoolsOnWorldLeave", Boolean.toString(this.clearPoolsOnWorldLeave));
        values.put("lazySearchTrees", Boolean.toString(this.lazySearchTrees));
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
