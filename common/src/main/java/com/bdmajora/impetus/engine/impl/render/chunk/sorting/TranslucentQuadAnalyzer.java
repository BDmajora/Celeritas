package com.bdmajora.impetus.engine.impl.render.chunk.sorting;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import com.bdmajora.impetus.engine.impl.render.chunk.sorting.trigger.NormalPlanes;
import com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import org.joml.Vector3f;

import java.util.BitSet;

public class TranslucentQuadAnalyzer {
    // X/Y/Z for each quad center
    private static final int EXPECTED_QUADS = 1000;
    /**
     * Cap on how many distinct (quantized) normals we track plane sets for. Real terrain overwhelmingly uses the
     * six axis-aligned directions plus a few fluid-surface slopes; anything past this cap (pathological modded
     * geometry) falls back to the coarse always-resort heuristic instead of paying unbounded memory here.
     */
    private static final int MAX_TRACKED_NORMALS = 16;
    private final FloatArrayList quadCenters = new FloatArrayList(EXPECTED_QUADS * 3);
    private final FloatArrayList quadNormals = new FloatArrayList(EXPECTED_QUADS * 3);
    private final Vector3f[] vertexPositions = new Vector3f[4];
    private final Vector3f currentNormal = new Vector3f();
    private final Vector3f globalNormal = new Vector3f();
    private final BitSet normalSigns = new BitSet(EXPECTED_QUADS);
    private static final BitSet EMPTY = new BitSet();
    // Linked map: keeps registration order stable so repeated meshes of identical content produce identical
    // trigger data.
    private final Int2ObjectLinkedOpenHashMap<PlaneAccumulator> planesByNormal = new Int2ObjectLinkedOpenHashMap<>();
    private boolean trackedNormalsOverflowed;
    private int currentVertex;
    private boolean hasDistinctNormals;

    private static final class PlaneAccumulator {
        final float nx, ny, nz;
        final FloatArrayList distances = new FloatArrayList();

        PlaneAccumulator(float nx, float ny, float nz) {
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
        }

        NormalPlanes build() {
            var values = this.distances.toFloatArray();
            java.util.Arrays.sort(values);

            // Deduplicate near-equal offsets; coplanar quads share one watched plane.
            int unique = 0;

            for (int i = 0; i < values.length; i++) {
                if (unique == 0 || values[i] - values[unique - 1] >= 1.0E-4f) {
                    values[unique++] = values[i];
                }
            }

            return new NormalPlanes(this.nx, this.ny, this.nz,
                    unique == values.length ? values : java.util.Arrays.copyOf(values, unique));
        }
    }

    public enum Level {
        /**
         * No sorting is required of the current section.
         */
        NONE,
        /**
         * Sorting is required once during meshing.
         */
        STATIC,
        /**
         * Sorting is required any time the camera moves.
         */
        DYNAMIC;

        public static final Level[] VALUES = values();

        public boolean requiresDynamicSorting() {
            return this.ordinal() >= Level.DYNAMIC.ordinal();
        }
    }

    public TranslucentQuadAnalyzer() {
        for(int i = 0; i < 4; i++) {
            vertexPositions[i] = new Vector3f();
        }
    }

    /**
     * @param triggerPlanes for {@link Level#DYNAMIC} states, the per-normal plane sets used for precise
     *                      camera-crossing re-sort triggering; {@code null} when unavailable (too many distinct
     *                      normals), in which case the caller must fall back to coarse movement-based triggering.
     */
    public record SortState(Level level, float[] centers, float[] normals, int centersLength, BitSet normalSigns, Vector3f sharedNormal, NormalPlanes[] triggerPlanes) {
        public static final SortState NONE = new SortState(Level.NONE, null, null, 0, null, null, null);

        public boolean requiresDynamicSorting() {
            return level.requiresDynamicSorting();
        }

        public SortState compactForStorage() {
            if(this == NONE || requiresDynamicSorting()) {
                return this;
            } else {
                return new SortState(level, null, null, 0, null, null, null);
            }
        }

        public static SortState compacted(SortState state) {
            return state != null ? state.compactForStorage() : null;
        }
    }

    private static BitSet cloneBits(BitSet bits) {
        if(bits.isEmpty()) {
            return EMPTY;
        } else {
            return (BitSet)bits.clone();
        }
    }

    private boolean areAllQuadsOnSamePlane() {
        // Let globalNormal = (a, b, c). Any plane with this normal vector is denoted by the equation ax + by + cz = d,
        // for some real number d.
        //
        // Next, we know that any quad has either globalNormal or -globalNormal as a normal vector. Suppose a quad q has center (x, y, z).
        // We define the "plane extension" of q as the unique plane in 3D space that q resides within. In particular,
        // any quad's plane extension (when all share parallel normals) is uniquely determined by the choice of d.
        //
        // If all quads are on the same plane, we don't need to sort at all. Otherwise, we need to use a static sort.
        // Recalling that d is given by ax + by + cz, and that we know all those variables for any quad, we can
        // easily determine if all quads reside in the same plane by computing this expression for each quad center,
        // and checking that we obtain at most one value.

        var centerArray = quadCenters.elements();

        float a = globalNormal.x, b = globalNormal.y, c = globalNormal.z;
        float d = a * centerArray[0] + b * centerArray[1] + c * centerArray[2];
        int nQuads = quadCenters.size() / 3;
        for(int quadIdx = 1; quadIdx < nQuads; quadIdx++) {
            int centerOff = quadIdx * 3;
            float candidateD = a * centerArray[centerOff + 0] + b * centerArray[centerOff + 1] + c * centerArray[centerOff + 2];
            if(Math.abs(candidateD - d) >= 1.0E-5F) {
                // Different planes
                return false;
            }
        }

        return true;
    }

    public SortState getSortState() {
        if(quadCenters.isEmpty()) {
            return SortState.NONE;
        } else {
            Level sortLevel;

            // Figure out what sort level is required
            if(hasDistinctNormals) {
                // Must use dynamic sort
                sortLevel = Level.DYNAMIC;
            } else {
                // If all quads are on the same plane we can use NONE sorting, otherwise we need to sort statically to put
                // them in the right order
                sortLevel = areAllQuadsOnSamePlane() ? Level.NONE : Level.STATIC;
            }

            SortState finalState;

            if (sortLevel == Level.NONE) {
                finalState = SortState.NONE;
            } else if (sortLevel.requiresDynamicSorting()) {
                // Clone everything
                finalState = new SortState(sortLevel, quadCenters.toArray(new float[0]), quadNormals.toArray(new float[0]), quadCenters.size(), cloneBits(normalSigns), new Vector3f(globalNormal), buildTriggerPlanes());
            } else {
                // Just make a thin wrapper around our backing objects
                finalState = new SortState(sortLevel, quadCenters.elements(), null, quadCenters.size(), normalSigns, globalNormal, null);
            }

            return finalState;
        }
    }

    public void clear() {
        quadCenters.clear();
        quadNormals.clear();
        currentVertex = 0;
        globalNormal.zero();
        normalSigns.clear();
        hasDistinctNormals = false;
        planesByNormal.clear();
        trackedNormalsOverflowed = false;
    }

    private NormalPlanes[] buildTriggerPlanes() {
        if (trackedNormalsOverflowed || planesByNormal.isEmpty()) {
            return null;
        }

        var planes = new NormalPlanes[planesByNormal.size()];
        int i = 0;

        for (var accumulator : planesByNormal.values()) {
            planes[i++] = accumulator.build();
        }

        return planes;
    }

    private void accumulatePlane(float centerX, float centerY, float centerZ) {
        if (trackedNormalsOverflowed) {
            return;
        }

        var key = NormalPlanes.quantize(currentNormal.x, currentNormal.y, currentNormal.z);
        var accumulator = planesByNormal.get(key);

        if (accumulator == null) {
            if (planesByNormal.size() >= MAX_TRACKED_NORMALS) {
                trackedNormalsOverflowed = true;
                planesByNormal.clear();
                return;
            }

            accumulator = new PlaneAccumulator(currentNormal.x, currentNormal.y, currentNormal.z);
            planesByNormal.put(key, accumulator);
        }

        // Use the group's representative normal so all offsets within a group live on one consistent axis.
        accumulator.distances.add(accumulator.nx * centerX + accumulator.ny * centerY + accumulator.nz * centerZ);
    }

    private void calculateNormal() {
        final Vector3f v0 = vertexPositions[0];

        final float x0 = v0.x;
        final float y0 = v0.y;
        final float z0 = v0.z;

        final Vector3f v1 = vertexPositions[1];

        final float x1 = v1.x;
        final float y1 = v1.y;
        final float z1 = v1.z;

        final Vector3f v2 = vertexPositions[2];

        final float x2 = v2.x;
        final float y2 = v2.y;
        final float z2 = v2.z;

        final Vector3f v3 = vertexPositions[3];

        final float x3 = v3.x;
        final float y3 = v3.y;
        final float z3 = v3.z;

        final float dx0 = x2 - x0;
        final float dy0 = y2 - y0;
        final float dz0 = z2 - z0;
        final float dx1 = x3 - x1;
        final float dy1 = y3 - y1;
        final float dz1 = z3 - z1;

        float normX = dy0 * dz1 - dz0 * dy1;
        float normY = dz0 * dx1 - dx0 * dz1;
        float normZ = dx0 * dy1 - dy0 * dx1;

        float l = (float) Math.sqrt(normX * normX + normY * normY + normZ * normZ);

        if (l != 0) {
            normX /= l;
            normY /= l;
            normZ /= l;
        }

        currentNormal.set(normX, normY, normZ);
    }

    private void captureQuad() {
        // The four positions in vertexPositions form a quad. Find its center
        float totalX = 0, totalY = 0, totalZ = 0;
        for (Vector3f vertex : vertexPositions) {
            totalX += vertex.x;
            totalY += vertex.y;
            totalZ += vertex.z;
        }

        var centers = quadCenters;
        int currentQuadIndex = centers.size() / 3;

        centers.ensureCapacity(centers.size() + 3);
        centers.add(totalX / 4);
        centers.add(totalY / 4);
        centers.add(totalZ / 4);

        // The normal is needed unconditionally now: sections that turn out to be DYNAMIC register their face
        // planes with the trigger index, which requires every quad's plane, not just those seen before the
        // distinct-normal flag tripped.
        calculateNormal();
        quadNormals.add(currentNormal.x);
        quadNormals.add(currentNormal.y);
        quadNormals.add(currentNormal.z);
        accumulatePlane(totalX / 4, totalY / 4, totalZ / 4);

        if(!hasDistinctNormals) {
            if(globalNormal.x == 0 && globalNormal.y == 0 && globalNormal.z == 0) {
                // No normal has been tracked thus far, choose this one
                globalNormal.set(currentNormal);
            } else {
                float dotProduct = globalNormal.dot(currentNormal);
                // Technically, only 1 and -1 imply that the quads share a normal. However, if the dot products
                // are very, very similar, we pretend they share a normal for optimization purposes. This is an
                // approximation that allows very slightly slanted water in edges of underwater lakes to be counted as
                // STATIC rather than DYNAMIC.
                if (Math.abs(dotProduct) >= 0.98) {
                    if (dotProduct < 0) {
                        // Flag this quad as being flipped relative to the global normal
                        normalSigns.set(currentQuadIndex);
                    }
                } else {
                    hasDistinctNormals = true;
                }
            }
        }
    }

    public void capture(ChunkVertexEncoder.Vertex vertex) {
        int i = currentVertex;
        vertexPositions[i].set(vertex.x, vertex.y, vertex.z);
        i++;
        if(i == 4) {
            captureQuad();
            i = 0;
        }
        currentVertex = i;
    }
}
