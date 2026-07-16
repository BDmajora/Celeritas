package com.bdmajora.impetus.engine.impl.render.chunk.compile.sorting;

import java.util.Arrays;

/**
 * A compact BSP ordering pass for translucent quads.
 * <p>
 * It intentionally does not split intersecting quads; Minecraft terrain overwhelmingly consists of flat faces,
 * panes, fluids, and modded quads whose centers are enough to pick a stable side of each plane. When the captured
 * normals are unusable, callers should fall back to center-distance sorting.
 */
final class BspTranslucencySorter {
    private static final float PLANE_EPSILON = 1.0E-5f;
    private static final int MAX_BSP_QUADS = 2048;

    private BspTranslucencySorter() {
    }

    static int[] sort(float[] centers, float[] normals, int quadCount, float cameraX, float cameraY, float cameraZ) {
        if (quadCount > MAX_BSP_QUADS || centers == null || normals == null
                || centers.length < quadCount * 3 || normals.length < quadCount * 3) {
            return null;
        }

        int[] left = new int[quadCount];
        int[] right = new int[quadCount];
        Arrays.fill(left, -1);
        Arrays.fill(right, -1);

        for (int quad = 0; quad < quadCount; quad++) {
            int normalOffset = quad * 3;
            float nx = normals[normalOffset];
            float ny = normals[normalOffset + 1];
            float nz = normals[normalOffset + 2];

            if (nx * nx + ny * ny + nz * nz < PLANE_EPSILON) {
                return null;
            }
        }

        int root = 0;

        for (int quad = 1; quad < quadCount; quad++) {
            insert(root, quad, centers, normals, left, right);
        }

        int[] order = new int[quadCount];
        int[] cursor = new int[] { 0 };
        emit(root, centers, normals, left, right, cameraX, cameraY, cameraZ, order, cursor);

        return cursor[0] == quadCount ? order : null;
    }

    private static void insert(int node, int quad, float[] centers, float[] normals, int[] left, int[] right) {
        float side = signedDistanceToPlane(quad, node, centers, normals);

        if (side < -PLANE_EPSILON) {
            if (left[node] == -1) {
                left[node] = quad;
            } else {
                insert(left[node], quad, centers, normals, left, right);
            }
        } else {
            if (right[node] == -1) {
                right[node] = quad;
            } else {
                insert(right[node], quad, centers, normals, left, right);
            }
        }
    }

    private static void emit(int node, float[] centers, float[] normals, int[] left, int[] right,
                             float cameraX, float cameraY, float cameraZ, int[] order, int[] cursor) {
        if (node == -1) {
            return;
        }

        float cameraSide = signedDistanceToPlane(cameraX, cameraY, cameraZ, node, centers, normals);

        if (cameraSide >= 0.0f) {
            emit(left[node], centers, normals, left, right, cameraX, cameraY, cameraZ, order, cursor);
            order[cursor[0]++] = node;
            emit(right[node], centers, normals, left, right, cameraX, cameraY, cameraZ, order, cursor);
        } else {
            emit(right[node], centers, normals, left, right, cameraX, cameraY, cameraZ, order, cursor);
            order[cursor[0]++] = node;
            emit(left[node], centers, normals, left, right, cameraX, cameraY, cameraZ, order, cursor);
        }
    }

    private static float signedDistanceToPlane(int quad, int planeQuad, float[] centers, float[] normals) {
        int centerOffset = quad * 3;
        return signedDistanceToPlane(centers[centerOffset], centers[centerOffset + 1], centers[centerOffset + 2],
                planeQuad, centers, normals);
    }

    private static float signedDistanceToPlane(float x, float y, float z, int planeQuad, float[] centers, float[] normals) {
        int planeOffset = planeQuad * 3;

        float px = centers[planeOffset];
        float py = centers[planeOffset + 1];
        float pz = centers[planeOffset + 2];
        float nx = normals[planeOffset];
        float ny = normals[planeOffset + 1];
        float nz = normals[planeOffset + 2];

        return nx * (x - px) + ny * (y - py) + nz * (z - pz);
    }
}
