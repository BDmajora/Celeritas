package com.bdmajora.impetus.engine.impl.render.chunk.lists;

import com.bdmajora.impetus.engine.impl.render.chunk.ChunkUpdateType;
import com.bdmajora.impetus.engine.impl.render.chunk.RenderSection;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;

// The render sections needing a rebuild, bucketed by how urgent that rebuild is
// byUpdateType maps each update type to its own queue, which is what lets an important rebuild jump ahead of a
// merely-nearby one
// hasAdditionalUpdates says whether more sections wanted rebuilding than were queued — the graph walk caps how
// many it enqueues per frame, and this tells the caller to come back rather than assume it is finished
// queueOverflowCounts records how many were dropped per type, for the debug overlay
public record ChunkRebuildLists(Map<ChunkUpdateType, ArrayDeque<RenderSection>> byUpdateType, boolean hasAdditionalUpdates, Map<ChunkUpdateType, Integer> queueOverflowCounts) {
    public static final ChunkRebuildLists EMPTY;

    // Sections queued for one update type
    public int getUpdateCount(ChunkUpdateType type) {
        return byUpdateType.get(type).size() + queueOverflowCounts.getOrDefault(type, 0);
    }

    // Nothing queued
    public boolean isEmpty() {
        if (hasAdditionalUpdates) {
            return false;
        }
        for (var queue : byUpdateType.values()) {
            if (!queue.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    static {
        Map<ChunkUpdateType, ArrayDeque<RenderSection>> rebuildLists = new EnumMap<>(ChunkUpdateType.class);

        for (var type : ChunkUpdateType.values()) {
            rebuildLists.put(type, new ArrayDeque<>());
        }

        EMPTY = new ChunkRebuildLists(rebuildLists, false, new EnumMap<>(ChunkUpdateType.class));
    }
}
