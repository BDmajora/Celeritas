package com.bdmajora.impetus.engine.impl.render.chunk.async;

import com.bdmajora.impetus.engine.impl.render.chunk.lists.SectionTicker;
import com.bdmajora.impetus.engine.impl.render.chunk.lists.VisibleChunkCollector;
import com.bdmajora.impetus.engine.impl.render.chunk.occlusion.OcclusionCuller;
import com.bdmajora.impetus.engine.impl.render.viewport.Viewport;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public final class ChunkCullTask implements Supplier<VisibleChunkCollector> {
    private final OcclusionCuller occlusionCuller;
    private final VisibleChunkCollector collector;
    private final Viewport viewport;
    private final float searchDistance;
    private final boolean useOcclusionCulling;
    private final int frame;
    private final SectionTicker sectionTicker;

    public ChunkCullTask(OcclusionCuller occlusionCuller,
                         VisibleChunkCollector collector,
                         Viewport viewport,
                         float searchDistance,
                         boolean useOcclusionCulling,
                         int frame,
                         @Nullable SectionTicker sectionTicker) {
        this.occlusionCuller = occlusionCuller;
        this.collector = collector;
        this.viewport = viewport;
        this.searchDistance = searchDistance;
        this.useOcclusionCulling = useOcclusionCulling;
        this.frame = frame;
        this.sectionTicker = sectionTicker;
    }

    // Blocks until the graph walk finishes and returns its collector
    @Override
    public VisibleChunkCollector get() {
        this.occlusionCuller.findVisible(this.collector, this.viewport, this.searchDistance, this.useOcclusionCulling, this.frame);

        // This may run on the async cull thread. SectionTicker implementations must only touch async-safe state here.
        if (this.sectionTicker != null) {
            this.sectionTicker.onRenderListUpdated(this.collector.getCollectedRenderLists());
        }

        return this.collector;
    }
}
