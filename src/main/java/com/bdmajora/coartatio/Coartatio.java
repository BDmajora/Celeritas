package com.bdmajora.coartatio;

import com.bdmajora.coartatio.dedup.ModelCaches;
import com.bdmajora.coartatio.dedup.ResourceLocationCaches;
import com.bdmajora.coartatio.dedup.StringPool;
import com.bdmajora.coartatio.dedup.TransformCaches;
import com.bdmajora.coartatio.state.CompactPropertyMaps;
import com.bdmajora.coartatio.state.ConditionCanonicalizer;
import com.bdmajora.coartatio.state.PropertyValueMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry points for Impetus' memory-compression subsystem.
 *
 * <p>Coartatio is a backport of <a href="https://github.com/jellysquid3/hydrogen-fabric">Hydrogen</a>
 * combined with the parts of FoamFix, LoliASM and FerriteCore that solve the same problems better on
 * 1.12.2. See {@code COARTATIO_ROADMAP.md} for the feature inventory.
 *
 * <p>Nothing here is a mod entry point in the FML sense — the mixins do the work and the lifecycle
 * hangs off the resource reload. This class exists to own the logger and the pool lifecycle so the
 * two are never driven from more than one place.
 */
public final class Coartatio {
    public static final Logger LOGGER = LogManager.getLogger("Coartatio");

    private Coartatio() {
    }

    /**
     * Called at the start of a resource reload, before any model is baked.
     *
     * <p>Pools that only exist to serve the bake are (re)opened here. Re-opening rather than clearing
     * matters: the previous reload's canonical arrays are still referenced by whatever survived it,
     * and we want the new pool to converge on the new resource pack's geometry rather than keep the
     * old pack's alive.
     */
    public static void onResourceReloadStart() {
        ModelCaches.open();
        TransformCaches.open();
        ConditionCanonicalizer.open();
    }

    /**
     * Called once every model has been baked.
     *
     * <p>The bake-scoped pools are closed here. Closing releases the hash sets, not their contents —
     * every quad keeps the canonical array it was given, we simply stop paying to track them. Quads
     * created after this point (dynamic models, runtime bakes) skip deduplication entirely, which is
     * the right trade: they are rare and they are the ones most likely to be mutated later.
     */
    public static void onResourceReloadFinish() {
        ModelCaches.close();
        TransformCaches.close();
        ConditionCanonicalizer.close();

        if (CoartatioConfig.get().logStatistics) {
            for (String line : statistics()) {
                LOGGER.info(line);
            }
            for (String line : MemoryReport.lines()) {
                LOGGER.info(line);
            }
        }
    }

    /**
     * Called when the player leaves a world or server.
     *
     * <p>FoamFix's {@code clClearCachesOnUnload}. Two pools grow with play rather than with loading:
     * NBT keys pick up every key seen in world data, and resource paths pick up dynamically
     * constructed locations such as downloaded skins. Neither shrinks on its own, so a long session
     * followed by a return to the main menu leaves both holding a world's worth of strings that the
     * next world will not reuse.
     *
     * <p>Resetting them frees the pools; strings already handed out stay valid and stay shared, they
     * just stop being tracked. Model and block-state pools are deliberately untouched — those are
     * keyed to resources and block registries, which survive a world change.
     */
    public static void onWorldLeave() {
        int freed = StringPool.NBT_KEYS.size() + ResourceLocationCaches.PATHS.size();

        StringPool.NBT_KEYS.clear();
        ResourceLocationCaches.PATHS.open();

        LOGGER.info("Released {} pooled strings on leaving the world", freed);
    }

    /** Human-readable pool statistics, one entry per line. Shared by the log and the F3 overlay. */
    public static List<String> statistics() {
        List<String> lines = new ArrayList<>();
        lines.add("Coartatio memory statistics");
        lines.add("  Resource domains: " + ResourceLocationCaches.DOMAINS);
        lines.add("  Resource paths:   " + ResourceLocationCaches.PATHS);
        lines.add("  Model variants:   " + ModelCaches.VARIANTS);
        lines.add("  Quad vertex data: " + ModelCaches.QUADS);
        lines.add("  Quads not pooled: " + ModelCaches.skippedSummary());
        lines.add("  NBT keys:         " + StringPool.NBT_KEYS);
        lines.add("  Camera transforms:" + TransformCaches.TRANSFORMS);
        lines.add("  Multipart preds:  " + ConditionCanonicalizer.statistics());

        if (CoartatioConfig.get().optimizeBlockStates) {
            lines.add("  Block states:     " + PropertyValueMapper.statistics());
            lines.add("  Property maps:    " + CompactPropertyMaps.statistics());
        }

        return lines;
    }

    /**
     * The single line Impetus adds to the F3 overlay.
     *
     * <p>Both counts are pooled-entry totals, which read low on purpose: they are what Coartatio
     * kept, and the interesting number is how much they stand in for. {@code QUADS.size()} survives
     * the pool being closed after the bake — before that was fixed this always displayed zero.
     */
    public static String debugOverlayLine() {
        return String.format("Coartatio: ~%s saved (/coartatio for detail)", MemoryReport.summary());
    }
}
