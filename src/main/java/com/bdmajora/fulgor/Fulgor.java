package com.bdmajora.fulgor;

import net.minecraftforge.fml.common.Loader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

// Entry points for Impetus' lighting subsystem. Fulgor is a backport of Phosphor (via the Phosphor-Forge
// -> Hesperus 1.12.2 lineage), folding in Alfheim's corrections; see FULGOR_ROADMAP.md for the feature inventory.
// Not an FML entry point itself — the mixins do the work, each World owns a LightingEngine. This class
// owns the logger, optional-mod detection the engine's hot path branches on, and the /fulgor counters.
public final class Fulgor {
    public static final Logger LOGGER = LogManager.getLogger("Fulgor");

    // 2 light types * 4 directions * 2 halves * (inwards + outwards); width of a chunk's neighbour-light-check
    // table, also the length it must have when read back from NBT
    public static final int BOUNDARY_FLAG_COUNT = 32;

    // Reports luminance for blocks with none of their own (dropped torch, held lantern); when present the
    // engine must ask it instead of the block state
    private static boolean dynamicLights;

    // A fluidlogged block has two states at one position; real opacity/luminance is the max of both, so
    // no block can use the cached fast path while this is installed
    private static boolean fluidloggedApi;

    // Whether the per-block light-info cache is usable; see useCachedBlockLightInfo()
    private static boolean cachedBlockLightInfo;

    // LongAdder rather than plain fields: single-player runs two engines (client world + integrated
    // server) on two threads, and a lost update under contention would make dedup look better than it is
    private static final LongAdder SCHEDULED = new LongAdder();
    private static final LongAdder DEDUPLICATED = new LongAdder();
    private static final LongAdder PROCESSED = new LongAdder();

    private Fulgor() {
    }

    // Called from FMLConstructionEvent, the earliest point Loader can answer and still safely
    // before the first World (and its LightingEngine) is constructed
    public static void detectCompatibility() {
        dynamicLights = Loader.isModLoaded("dynamiclights");
        fluidloggedApi = Loader.isModLoaded("fluidlogged_api");

        // The config switch also gates the Block mixin, so without it the cast the fast path performs
        // would fail rather than merely mislead.
        cachedBlockLightInfo = FulgorConfig.get().cacheBlockLightInfo && !dynamicLights && !fluidloggedApi;
    }

    public static boolean hasDynamicLights() {
        return dynamicLights;
    }

    public static boolean hasFluidloggedApi() {
        return fluidloggedApi;
    }

    // Resolved once in detectCompatibility() rather than checked lazily, since this is read for six
    // neighbours of every position in every batch. Dynamic Lights and Fluidlogged API both answer
    // "what is at this position" rather than "what is this state", breaking the cache's assumption,
    // so the cache is bypassed entirely rather than consulted and second-guessed.
    public static boolean useCachedBlockLightInfo() {
        return cachedBlockLightInfo;
    }

    public static void recordScheduled() {
        SCHEDULED.increment();
    }

    public static void recordDeduplicated() {
        DEDUPLICATED.increment();
    }

    // Recorded once per pass rather than per position; sits on the engine's innermost loop
    public static void recordProcessed(long count) {
        PROCESSED.add(count);
    }

    // Human-readable engine statistics, one entry per line; shared by the log and /fulgor
    public static List<String> statistics() {
        long scheduled = SCHEDULED.sum();

        List<String> lines = new ArrayList<>();
        lines.add("Fulgor lighting statistics");
        lines.add("  Updates scheduled:   " + scheduled);
        lines.add("  Collapsed as dupes:  " + DEDUPLICATED.sum() + " (" + percentOfScheduled(DEDUPLICATED.sum()) + ")");
        lines.add("  Positions evaluated: " + PROCESSED.sum());
        return lines;
    }

    // Single line Impetus adds to the F3 overlay; the deduplication rate is the interesting number —
    // it's the share of lighting work that never happened, since vanilla would've evaluated all of it
    public static String debugOverlayLine() {
        return String.format("Fulgor: %s updates, %s deduped (/fulgor for detail)",
                compact(SCHEDULED.sum()), percentOfScheduled(DEDUPLICATED.sum()));
    }

    private static String percentOfScheduled(long value) {
        long scheduled = SCHEDULED.sum();

        return scheduled == 0 ? "0%" : String.format("%.0f%%", (value * 100.0D) / scheduled);
    }

    private static String compact(long value) {
        if (value < 1_000L) {
            return Long.toString(value);
        }
        if (value < 1_000_000L) {
            return String.format("%.1fk", value / 1_000.0D);
        }
        return String.format("%.1fM", value / 1_000_000.0D);
    }
}
