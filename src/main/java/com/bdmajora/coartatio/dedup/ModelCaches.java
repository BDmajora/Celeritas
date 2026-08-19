package com.bdmajora.coartatio.dedup;

import com.bdmajora.coartatio.CoartatioConfig;
import it.unimi.dsi.fastutil.ints.IntArrays;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bake-scoped pools, opened and closed around a resource reload by
 * {@link com.bdmajora.coartatio.Coartatio}.
 *
 * <p>{@link #QUADS} is the largest single win in Phase 1. Every full block face in the game bakes to
 * a 28-int vertex array, and the overwhelming majority of them are byte-identical: every plain cube
 * with the same texture on the same side produces the same array. In a large pack this collapses
 * millions of arrays down to tens of thousands.
 *
 * <p><b>Every array handed out by {@link #QUADS} must be treated as immutable.</b> See
 * {@code CoartatioBakedQuadMixin} for the (deliberately conservative) rule that enforces it.
 */
public final class ModelCaches {
    public static final DeduplicationCache<int[]> QUADS =
            new DeduplicationCache<>("Quad vertex data", CoartatioConfig.get().poolSizeLimit,
                    IntArrays.HASH_STRATEGY);

    /**
     * The {@code variant} of a {@code ModelResourceLocation} — {@code "normal"},
     * {@code "facing=north,half=bottom"} and friends. {@code "normal"} and {@code "inventory"} alone
     * account for a large fraction of all instances.
     */
    public static final DeduplicationCache<String> VARIANTS =
            new DeduplicationCache<>("Model variants", CoartatioConfig.get().poolSizeLimit);

    /**
     * Quads rejected by the immutability rule, counted per implementing class.
     *
     * <p>Instrumentation rather than diagnostics: "the pool is empty" is indistinguishable from "the
     * injection never fired" and from "every quad was a subclass" unless something counts the
     * difference. The first build of the quad mixin bound to the wrong constructor and pooled
     * nothing, and this is what tells the two apart next time.
     */
    private static final Map<String, Integer> SKIPPED_BY_CLASS = new HashMap<>();

    private ModelCaches() {
    }

    public static void recordSkippedQuad(Class<?> type) {
        String name = type.getName();

        synchronized (SKIPPED_BY_CLASS) {
            SKIPPED_BY_CLASS.merge(name, 1, Integer::sum);
        }
    }

    /** Human-readable summary of quads that were not pooled, most frequent first. */
    public static String skippedSummary() {
        synchronized (SKIPPED_BY_CLASS) {
            if (SKIPPED_BY_CLASS.isEmpty()) {
                return "none";
            }

            return SKIPPED_BY_CLASS.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(5)
                    .map(entry -> entry.getValue() + "x " + entry.getKey())
                    .collect(Collectors.joining(", "));
        }
    }

    public static void open() {
        QUADS.open();
        VARIANTS.open();

        synchronized (SKIPPED_BY_CLASS) {
            SKIPPED_BY_CLASS.clear();
        }
    }

    public static void close() {
        QUADS.close();
        // Variants are not closed with the bake: ModelResourceLocation is constructed throughout the
        // session by item rendering and by mods, and the pool is small enough to keep indefinitely.
    }
}
