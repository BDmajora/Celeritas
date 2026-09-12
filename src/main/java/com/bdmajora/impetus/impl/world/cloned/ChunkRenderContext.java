package com.bdmajora.impetus.impl.world.cloned;

import net.minecraft.world.gen.structure.StructureBoundingBox;
import com.bdmajora.impetus.engine.impl.util.position.SectionPos;

// Snapshot of a chunk section and its neighbors handed to the render thread, decoupled from the live world
public class ChunkRenderContext {
    private final SectionPos sectionCoord;
    private final ClonedChunkSection[] sections;
    private final StructureBoundingBox volume;

    public ChunkRenderContext(SectionPos sectionCoord, ClonedChunkSection[] sections, StructureBoundingBox volume) {
        this.sectionCoord = sectionCoord;
        this.sections = sections;
        this.volume = volume;
    }

    // The 3x3x3 cloned sections around the origin
    public ClonedChunkSection[] getSections() {
        return this.sections;
    }

    // The section being built
    public SectionPos getOrigin() {
        return this.sectionCoord;
    }

    // The block volume the slice covers
    public StructureBoundingBox getVolume() {
        return this.volume;
    }
}