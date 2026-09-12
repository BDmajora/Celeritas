package com.bdmajora.impetus.engine.impl.model.quad.properties;

// Vertex orientation of a model quad, used to re-order vertices consistently and eliminate anisotropy shading issues
public enum ModelQuadOrientation {
    NORMAL(new int[] { 0, 1, 2, 3 }),
    FLIP(new int[] { 1, 2, 3, 0 });

    private final int[] indices;

    ModelQuadOrientation(int[] indices) {
        this.indices = indices;
    }

    // returns the re-oriented index of vertex idx
    public int getVertexIndex(int idx) {
        return this.indices[idx];
    }

    // determines the orientation of the vertices in the quad from their brightness
    public static ModelQuadOrientation orientByBrightness(float[] brightnesses, int[] lightmaps) {
        // If one side of the quad is brighter, flip the sides
        float br02 = brightnesses[0] + brightnesses[2];
        float br13 = brightnesses[1] + brightnesses[3];
        if (br02 > br13) {
            return NORMAL;
        } else if (br02 < br13) {
            return FLIP;
        }

        // If one side of the quad is darker, flip the sides
        int lm02 = lightmaps[0] + lightmaps[2];
        int lm13 = lightmaps[1] + lightmaps[3];
        if (lm02 <= lm13) {
            return NORMAL;
        } else {
            return FLIP;
        }
    }
}
