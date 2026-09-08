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

// Backport of Hydrogen (jellysquid3/hydrogen-fabric) plus the parts of FoamFix, LoliASM and
// FerriteCore solving the same problems on 1.12.2. Not an FML entry point itself — mixins do
// the work; this owns the logger and pool lifecycle so both are driven from one place.
public final class Coartatio {
    public static final Logger LOGGER = LogManager.getLogger("Coartatio");

    private Coartatio() {
    }

    // Re-opens (not clears) the bake-scoped pools: the previous reload's canonical arrays are
    // still referenced by whatever survived it, and the new pool should converge on the new
    // pack's geometry rather than keep the old pack's alive.
    public static void onResourceReloadStart() {
        ModelCaches.open();
        TransformCaches.open();
        ConditionCanonicalizer.open();
    }

    // Closing releases the tracking hash sets, not the pooled contents — every quad keeps its
    // canonical array. Quads baked after this point skip dedup entirely (rare, and most likely
    // to be mutated later, so not worth tracking).
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

    // Equivalent to FoamFix's clClearCachesOnUnload. NBT keys and resource paths grow with play
    // (not loading) and never shrink on their own, so reset them here; strings already handed
    // out stay valid, they just stop being tracked. Model/block-state pools survive a world
    // change since they're keyed to resources and registries, not the world.
    public static void onWorldLeave() {
        int freed = StringPool.NBT_KEYS.size() + ResourceLocationCaches.PATHS.size();

        StringPool.NBT_KEYS.clear();
        ResourceLocationCaches.PATHS.open();

        LOGGER.info("Released {} pooled strings on leaving the world", freed);
    }

    // Shared by the log and the F3 overlay.
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

    // Counts read low on purpose: they're what Coartatio kept, not what it stands in for.
    // QUADS.size() survives the pool closing after bake — before that fix this always showed zero.
    public static String debugOverlayLine() {
        return String.format("Coartatio: ~%s saved (/coartatio for detail)", MemoryReport.summary());
    }
}
