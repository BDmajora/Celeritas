package com.bdmajora.impetus.umbra.pipeline.shadow;

// The four frustum planes adjacent to a given plane, for extruding edge planes towards the light (Iris's NeighboringPlaneSet, a class not a record under --release 8); only three instances since the two planes on an axis share neighbours, and planeIndex >>> 1 picks it
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

    // >>> 1 turns a plane index into its axis index, which is exactly the TABLE index
    public static NeighboringPlaneSet forPlane(int planeIndex) {
        return TABLE[planeIndex >>> 1];
    }

    // First adjacent plane index
    public int plane0() {
        return this.plane0;
    }

    // Second adjacent plane index
    public int plane1() {
        return this.plane1;
    }

    // Third adjacent plane index
    public int plane2() {
        return this.plane2;
    }

    // Fourth adjacent plane index
    public int plane3() {
        return this.plane3;
    }
}
