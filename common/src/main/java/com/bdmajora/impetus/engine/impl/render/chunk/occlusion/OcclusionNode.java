package com.bdmajora.impetus.engine.impl.render.chunk.occlusion;

import lombok.Getter;
import lombok.Setter;
import com.bdmajora.impetus.engine.impl.render.chunk.AbstractSection;
import com.bdmajora.impetus.engine.impl.render.chunk.RenderSection;

public class OcclusionNode extends AbstractSection {
    private int incomingDirections;
    private int lastVisibleFrame = -1;

    private int adjacentMask;
    public OcclusionNode
            adjacentDown,
            adjacentUp,
            adjacentNorth,
            adjacentSouth,
            adjacentWest,
            adjacentEast;

    // Encodes which faces of this section can see which other faces, for occlusion culling
    @Getter
    @Setter
    private long visibilityData = VisibilityEncoding.NULL;

    private final RenderSection section;
    @Getter
    private final int renderRegionId;

    public OcclusionNode(RenderSection section) {
        super(section.getChunkX(), section.getChunkY(), section.getChunkZ());
        this.section = section;
        this.renderRegionId = section.getRegion().getId();
    }

    // Neighbour, or null at a world edge
    public OcclusionNode getAdjacent(int direction) {
        return switch (direction) {
            case GraphDirection.DOWN -> this.adjacentDown;
            case GraphDirection.UP -> this.adjacentUp;
            case GraphDirection.NORTH -> this.adjacentNorth;
            case GraphDirection.SOUTH -> this.adjacentSouth;
            case GraphDirection.WEST -> this.adjacentWest;
            case GraphDirection.EAST -> this.adjacentEast;
            default -> null;
        };
    }

    // Links a neighbour and updates the mask
    public void setAdjacentNode(int direction, OcclusionNode node) {
        if (node == null) {
            this.adjacentMask &= ~GraphDirectionSet.of(direction);
        } else {
            this.adjacentMask |= GraphDirectionSet.of(direction);
        }

        switch (direction) {
            case GraphDirection.DOWN -> this.adjacentDown = node;
            case GraphDirection.UP -> this.adjacentUp = node;
            case GraphDirection.NORTH -> this.adjacentNorth = node;
            case GraphDirection.SOUTH -> this.adjacentSouth = node;
            case GraphDirection.WEST -> this.adjacentWest = node;
            case GraphDirection.EAST -> this.adjacentEast = node;
            default -> { }
        }
    }

    // Which of six neighbours exist
    public int getAdjacentMask() {
        return this.adjacentMask;
    }

    // Stamped when reached by a walk
    public void setLastVisibleFrame(int frame) {
        this.lastVisibleFrame = frame;
    }

    // For the once-per-frame visit check
    public int getLastVisibleFrame() {
        return this.lastVisibleFrame;
    }

    // Directions the walk entered from this frame
    public int getIncomingDirections() {
        return this.incomingDirections;
    }

    // ORs in another entry direction
    public void addIncomingDirections(int directions) {
        this.incomingDirections |= directions;
    }

    // Resets for a new frame
    public void setIncomingDirections(int directions) {
        this.incomingDirections = directions;
    }

    // The section this node wraps
    public RenderSection getRenderSection() {
        return this.section;
    }
}
