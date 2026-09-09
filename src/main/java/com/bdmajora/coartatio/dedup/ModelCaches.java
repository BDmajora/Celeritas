package com.bdmajora.coartatio.dedup;

import com.bdmajora.coartatio.CoartatioConfig;
import it.unimi.dsi.fastutil.ints.IntArrays;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

// Bake-scoped pools, opened and closed around a resource reload by Coartatio
public final class ModelCaches {
    // The largest single saving of the lot. Every full block face bakes to a 28-int vertex array and the
    // overwhelming majority are byte-identical, since every plain cube with the same texture on the same side
    // produces the same array. On a large pack this collapses millions of arrays into tens of thousands
    // IntArrays.HASH_STRATEGY is what makes it work: it hashes and compares CONTENTS, so two equal arrays from
    // different models pool together, which reference equality would never catch
    // Every array handed out here must be treated as immutable — see CoartatioBakedQuadMixin for the
    // deliberately conservative rule that enforces it
    public static final DeduplicationCache<int[]> QUADS =
            new DeduplicationCache<>("Quad vertex data", CoartatioConfig.get().poolSizeLimit,
                    IntArrays.HASH_STRATEGY);

    // The variant string of a ModelResourceLocation: "normal", "inventory", "facing=north,half=bottom" and so
    // on. The first two alone account for a large fraction of every instance in the game
    public static final DeduplicationCache<String> VARIANTS =
            new DeduplicationCache<>("Model variants", CoartatioConfig.get().poolSizeLimit);

    // Quads the immutability rule refused, counted per implementing class
    // Instrumentation, not diagnostics: without it "the pool is empty" is indistinguishable from "the injection
    // never fired" and from "every quad turned out to be a subclass". The first build of the quad mixin bound to
    // the wrong constructor and pooled nothing at all, and this is what tells those apart next time
    private static final Map<String, Integer> SKIPPED_BY_CLASS = new HashMap<>();

    private ModelCaches() {
    }

    // Called from the quad mixin's reject path; synchronised because quad construction is not confined to one
    // thread once mods are in the picture
    public static void recordSkippedQuad(Class<?> type) {
        String name = type.getName();

        synchronized (SKIPPED_BY_CLASS) {
            SKIPPED_BY_CLASS.merge(name, 1, Integer::sum);
        }
    }

    // One line for the memory report: the five commonest rejected classes, most frequent first
    // Capped at five because a broken mod can produce a long tail nobody will read
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

    // Called at the start of a resource reload, so each bake is measured on its own
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
