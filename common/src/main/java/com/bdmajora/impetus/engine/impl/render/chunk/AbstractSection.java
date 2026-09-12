package com.bdmajora.impetus.engine.impl.render.chunk;

import com.bdmajora.impetus.engine.impl.render.chunk.region.RenderRegion;
import com.bdmajora.impetus.engine.impl.util.PositionUtil;

public abstract class AbstractSection {
    // Chunk Section State
    private final int chunkX, chunkY, chunkZ;

    private final int sectionIndex;

    public AbstractSection(int chunkX, int chunkY, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkY = chunkY;
        this.chunkZ = chunkZ;

        int rX = this.getChunkX() & (RenderRegion.REGION_WIDTH - 1);
        int rY = this.getChunkY() & (RenderRegion.REGION_HEIGHT - 1);
        int rZ = this.getChunkZ() & (RenderRegion.REGION_LENGTH - 1);

        // Local index within the region, used to look up region-local render data
        this.sectionIndex = LocalSectionIndex.pack(rX, rY, rZ);
    }

    // First block x
    public final int getOriginX() {
        return this.chunkX << 4;
    }

    // First block y
    public final int getOriginY() {
        return this.chunkY << 4;
    }

    // First block z
    public final int getOriginZ() {
        return this.chunkZ << 4;
    }

    // Centre block x
    public final int getCenterX() {
        return this.getOriginX() + 8;
    }

    // Centre block y
    public final int getCenterY() {
        return this.getOriginY() + 8;
    }

    // Centre block z
    public final int getCenterZ() {
        return this.getOriginZ() + 8;
    }

    // Section x
    public final int getChunkX() {
        return this.chunkX;
    }

    // Section y
    public final int getChunkY() {
        return this.chunkY;
    }

    // Section z
    public final int getChunkZ() {
        return this.chunkZ;
    }

    // From the centre
    public final float getSquaredDistance(float x, float y, float z) {
        float xDist = x - this.getCenterX();
        float yDist = y - this.getCenterY();
        float zDist = z - this.getCenterZ();

        return (xDist * xDist) + (yDist * yDist) + (zDist * zDist);
    }

    // Shares an axis with another section
    public final boolean isAlignedWithSectionOnGrid(int otherX, int otherY, int otherZ) {
        return this.chunkX == otherX || this.chunkY == otherY || this.chunkZ == otherZ;
    }

    // Local index within its region
    public final int getSectionIndex() {
        return this.sectionIndex;
    }

    // From the centre to a block
    public final float getSquaredDistanceFromBlockCenter(int x, int y, int z) {
        return this.getSquaredDistance(x + 0.5f, y + 0.5f, z + 0.5f);
    }

    // Packed section coordinates
    public final long positionAsLong() {
        return PositionUtil.packSection(this.chunkX, this.chunkY, this.chunkZ);
    }

    // For debugging
    @Override
    public String toString() {
        return String.format("%s at chunk (%d, %d, %d) from (%d, %d, %d) to (%d, %d, %d)",
                this.getClass().getSimpleName(),
                this.chunkX, this.chunkY, this.chunkZ,
                this.getOriginX(), this.getOriginY(), this.getOriginZ(),
                this.getOriginX() + 15, this.getOriginY() + 15, this.getOriginZ() + 15);
    }
}
