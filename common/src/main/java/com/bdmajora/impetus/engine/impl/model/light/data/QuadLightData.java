package com.bdmajora.impetus.engine.impl.model.light.data;

// Computed light data for a quad, indexed in the same vertex order as the quad itself
public class QuadLightData {
    // Per-vertex brightness, normalized 0..1
    public final float[] br = new float[4];

    // Per-vertex lightmap texture coords
    public final int[] lm = new int[4];
}
