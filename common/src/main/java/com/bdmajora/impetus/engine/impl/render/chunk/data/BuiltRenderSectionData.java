package com.bdmajora.impetus.engine.impl.render.chunk.data;

import com.bdmajora.impetus.engine.impl.render.chunk.lists.RenderVisualsService;
import org.jetbrains.annotations.MustBeInvokedByOverriders;

import java.util.Objects;

// What a finished build produced for one render section
// Extended per game version rather than being final, so a version-specific renderer can hang its own data off a
// section without the shared engine having to know about it
public class BuiltRenderSectionData {
    public boolean hasBlockGeometry;
    public long visibilityData;

    public int getVisualBitmaskForSection() {
        return this.hasBlockGeometry ? (1 << RenderVisualsService.HAS_BLOCK_GEOMETRY) : 0;
    }

    @MustBeInvokedByOverriders
    public void bake() {

    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        BuiltRenderSectionData that = (BuiltRenderSectionData) o;
        return hasBlockGeometry == that.hasBlockGeometry && visibilityData == that.visibilityData;
    }

    @Override
    public int hashCode() {
        return Objects.hash(hasBlockGeometry, visibilityData);
    }
}
