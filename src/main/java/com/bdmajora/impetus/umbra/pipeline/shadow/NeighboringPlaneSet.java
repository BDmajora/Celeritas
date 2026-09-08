package com.bdmajora.impetus.umbra.pipeline.shadow;

/**
 * The four frustum planes adjacent to a given plane, used when extruding edge planes. Port of Umbra's
 * {@code shadows.frustum.advanced.NeighboringPlaneSet} (a record upstream; a plain class here for {@code --release 8}).
 * <p>
 * Planes are ordered -X, +X, -Y, +Y, far, near, so {@code planeIndex >>> 1} selects the axis and every plane on one
 * axis shares the same set of neighbours.
 */
public final class NeighboringPlaneSet {
    private static final NeighboringPlaneSet FOR_PLUS_X = new NeighboringPlaneSet(2, 3, 4, 5);
    private static final NeighboringPlaneSet FOR_PLUS_Y = new NeighboringPlaneSet(0, 1, 4, 5);
    private static final NeighboringPlaneSet FOR_PLUS_Z = new NeighboringPlaneSet(0, 1, 2, 3);

    private static final NeighboringPlaneSet[] TABLE = {FOR_PLUS_X, FOR_PLUS_Y, FOR_PLUS_Z};

    private final int plane0;
    private final int plane1;
    private final int plane2;
    private final int plane3;

    private NeighboringPlaneSet(int plane0, int plane1, int plane2, int plane3) {
        this.plane0 = plane0;
        this.plane1 = plane1;
        this.plane2 = plane2;
        this.plane3 = plane3;
    }

    public static NeighboringPlaneSet forPlane(int planeIndex) {
        return TABLE[planeIndex >>> 1];
    }

    public int plane0() {
        return this.plane0;
    }

    public int plane1() {
        return this.plane1;
    }

    public int plane2() {
        return this.plane2;
    }

    public int plane3() {
        return this.plane3;
    }
}
