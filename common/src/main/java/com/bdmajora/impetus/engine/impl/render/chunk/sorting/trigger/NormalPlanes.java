package com.bdmajora.impetus.engine.impl.render.chunk.sorting.trigger;

/**
 * The set of geometry planes within one render section that share a (quantized) facing direction: a unit-ish
 * normal vector plus the sorted, deduplicated list of plane offsets {@code d = n · quadCenter} in
 * <em>section-local</em> coordinates.
 * <p>
 * The relative draw order of two translucent quads with the same normal can only change when the camera crosses
 * one of their planes, so these are exactly the surfaces the {@link TranslucencyTriggerIndex} needs to watch to
 * know when a section must be re-sorted.
 */
public record NormalPlanes(float nx, float ny, float nz, float[] distances) {
    /**
     * Quantization granularity for grouping nearly-parallel normals. 126 steps per axis keeps the grouping error
     * well under the epsilon used when testing plane crossings, while collapsing float noise from the mesher.
     */
    private static final int QUANT_SCALE = 126;

    public float minDistance() {
        return this.distances[0];
    }

    public float maxDistance() {
        return this.distances[this.distances.length - 1];
    }

    /**
     * Packs a direction into a stable integer key. Inputs must be (approximately) unit length.
     */
    public static int quantize(float x, float y, float z) {
        int qx = Math.round(x * QUANT_SCALE) + 128;
        int qy = Math.round(y * QUANT_SCALE) + 128;
        int qz = Math.round(z * QUANT_SCALE) + 128;

        return (qx << 16) | (qy << 8) | qz;
    }
}
