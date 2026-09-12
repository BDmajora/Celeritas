package com.bdmajora.impetus.engine.impl.render.chunk.lists;

import java.util.List;

public interface SectionTicker {
    void tickVisibleRenders();
    void onRenderListUpdated(List<ChunkRenderList> renderLists);

    // Defaults to empty
    default String getDebugString() {
        return "";
    }
}
