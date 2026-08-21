package com.bdmajora.fulgor;

import net.minecraftforge.fml.common.Loader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Entry points for Impetus' lighting subsystem.
 *
 * <p>Fulgor is a backport of <a href="https://github.com/CaffeineMC/phosphor-fabric">Phosphor</a>,
 * built on the 1.12.2 lineage that Phosphor itself started (Phosphor-Forge → Hesperus) and folding in
 * the corrections Alfheim made to it. See {@code FULGOR_ROADMAP.md} for the feature inventory.
 *
 * <p>Nothing here is a mod entry point in the FML sense — the mixins do the work and every lighting
 * engine belongs to a {@code World}. This class owns the logger, the optional-mod detection that the
 * engine's hot path branches on, and the counters behind {@code /fulgor}.
 */
public final class Fulgor {
    public static final Logger LOGGER = LogManager.getLogger("Fulgor");

    /**
     * {@code 2 light types * 4 directions * 2 halves * (inwards + outwards)}.
     *
     * <p>The width of a chunk's neighbour-light-check table. Also the length the table must have when
     * read back from NBT, which is why it is a shared constant rather than a local.
     */
    public static final int BOUNDARY_FLAG_COUNT = 32;

    /**
     * Whether Dynamic Lights is present.
     *
     * <p>It reports luminance for blocks that have none of their own (a dropped torch, a held
     * lantern), so when it is installed the engine has to ask it rather than the block state.
     */
    private static boolean dynamicLights;

    /**
     * Whether Fluidlogged API is present.
     *
     * <p>A fluidlogged block has two states in one position, and the position's real opacity and
     * luminance are the maximum of both. When it is installed no block can use the cached fast path.
     */
    private static boolean fluidloggedApi;

    /** Whether the per-block light-info cache is usable; see {@link #useCachedBlockLightInfo()}. */
    private static boolean cachedBlockLightInfo;

    /**
     * Diagnostic counters, one per stage of the pipeline.
     *
     * <p>{@link LongAdder} rather than plain fields because a single-player session runs two engines —
     * the client world's and the integrated server's — on two threads, and a counter that silently
     * loses updates under contention would make the deduplication rate look better than it is.
     */
    /** Positions handed to {@code World.checkLightFor} since launch. */
    private static final LongAdder SCHEDULED = new LongAdder();
    /** Positions the queues rejected because the same position was already pending. */
    private static final LongAdder DEDUPLICATED = new LongAdder();
    /** Positions actually taken off a queue and evaluated. */
    private static final LongAdder PROCESSED = new LongAdder();

    private Fulgor() {
    }

    /**
     * Resolves the optional mods the engine's hot path branches on.
     *
     * <p>Called from {@code FMLConstructionEvent}, which is the earliest point {@link Loader} can
     * answer and still comfortably before the first {@code World} — and therefore the first
     * {@code LightingEngine} — is constructed.
     */
    public static void detectCompatibility() {
        dynamicLights = Loader.isModLoaded("dynamiclights");
        fluidloggedApi = Loader.isModLoaded("fluidlogged_api");

        // The config switch also gates the Block mixin, so without it the cast the fast path performs
        // would fail rather than merely mislead.
        cachedBlockLightInfo = FulgorConfig.get().cacheBlockLightInfo && !dynamicLights && !fluidloggedApi;

        if (dynamicLights) {
            LOGGER.info("Dynamic Lights detected; block luminance will be queried through it");
        }

        if (fluidloggedApi) {
            LOGGER.info("Fluidlogged API detected; fluid states will be folded into opacity and luminance");
        }
    }

    public static boolean hasDynamicLights() {
        return dynamicLights;
    }

    public static boolean hasFluidloggedApi() {
        return fluidloggedApi;
    }

    /**
     * Whether the engine may take {@code LightInfoBlock}'s fast path.
     *
     * <p>Resolved once, in {@link #detectCompatibility()}, rather than checked lazily: this is read for
     * six neighbours of every position in every batch, and both of its inputs are fixed for the session
     * by the time the first world exists.
     *
     * <p>Dynamic Lights and Fluidlogged API both answer "what is at this position" rather than "what is
     * this state", which is exactly the assumption the cache is built on. With either loaded the cache
     * is bypassed entirely rather than consulted and then second-guessed.
     */
    public static boolean useCachedBlockLightInfo() {
        return cachedBlockLightInfo;
    }

    public static void recordScheduled() {
        SCHEDULED.increment();
    }

    public static void recordDeduplicated() {
        DEDUPLICATED.increment();
    }

    /** Recorded once per pass rather than per position; this sits on the engine's innermost loop. */
    public static void recordProcessed(long count) {
        PROCESSED.add(count);
    }

    /** Human-readable engine statistics, one entry per line. Shared by the log and {@code /fulgor}. */
    public static List<String> statistics() {
        long scheduled = SCHEDULED.sum();

        List<String> lines = new ArrayList<>();
        lines.add("Fulgor lighting statistics");
        lines.add("  Updates scheduled:   " + scheduled);
        lines.add("  Collapsed as dupes:  " + DEDUPLICATED.sum() + " (" + percentOfScheduled(DEDUPLICATED.sum()) + ")");
        lines.add("  Positions evaluated: " + PROCESSED.sum());
        return lines;
    }

    /**
     * The single line Impetus adds to the F3 overlay.
     *
     * <p>The interesting number is the deduplication rate: vanilla and every un-deduplicated Phosphor
     * derivative would have evaluated all of {@link #scheduled}, so the share collapsed here is the
     * share of the lighting work that never happened.
     */
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
