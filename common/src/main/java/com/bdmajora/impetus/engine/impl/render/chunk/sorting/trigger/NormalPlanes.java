package com.bdmajora.impetus.engine.impl.render.chunk.sorting.trigger;

// Geometry planes in one section sharing a quantized normal: the normal plus sorted, deduplicated section-local offsets d = n . quadCenter, which are exactly the surfaces whose crossing can change draw order
public record NormalPlanes(float nx, float ny, float nz, float[] distances) {
    // Quantization granularity for grouping nearly-parallel normals; 126 steps per axis stays well under the plane-crossing epsilon while collapsing mesher float noise
    private static final int QUANT_SCALE = 126;

    // Nearest plane along this normal
    public float minDistance() {
        return this.distances[0];
    }

    // Farthest plane along this normal
    public float maxDistance() {
        return this.distances[this.distances.length - 1];
    }

    // packs a direction into a stable integer key; inputs must be (approximately) unit length
    public static int quantize(float x, float y, float z) {
        int qx = Math.round(x * QUANT_SCALE) + 128;
        int qy = Math.round(y * QUANT_SCALE) + 128;
        int qz = Math.round(z * QUANT_SCALE) + 128;

        return (qx << 16) | (qy << 8) | qz;
    }
}
