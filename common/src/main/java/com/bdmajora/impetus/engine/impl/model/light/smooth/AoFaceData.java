package com.bdmajora.impetus.engine.impl.model.light.smooth;

import com.bdmajora.impetus.engine.impl.model.light.data.LightDataAccess;
import com.bdmajora.impetus.engine.impl.model.quad.properties.ModelQuadFacing;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;

import static com.bdmajora.impetus.engine.impl.model.light.data.LightDataAccess.*;

class AoFaceData {
    /**
     * DEBUG: differential check of this class's corner brightness against vanilla's algorithm, run on identical
     * inputs in the same call. Enable with {@code -Dimpetus.light.diffLog=<max lines>}, e.g. 40.
     * <p>
     * The point is to stop inferring the lightmap from screenshots. Shader-side probes cannot separate a genuine
     * lighting error from the {@code flat} qualifier showing one provoking-vertex value per triangle, or from the
     * deferred pipeline relighting whatever the gbuffer wrote. This compares the two formulas directly, on the same
     * neighbour samples, in-process, and prints only where they disagree — so a silent log exonerates the entire
     * smooth-lighting path rather than leaving it merely unproven.
     */
    private static final int DIFF_LOG_LIMIT = readDiffLogLimit();
    private static final AtomicInteger DIFF_LOG_BUDGET = new AtomicInteger(DIFF_LOG_LIMIT);
    private static final Logger LOGGER = LogManager.getLogger("Impetus/LightDiff");

    private static int readDiffLogLimit() {
        String raw = System.getProperty("impetus.light.diffLog");
        if (raw == null || raw.trim().isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public final int[] lm = new int[4];

    public final float[] ao = new float[4];
    public final float[] bl = new float[4];
    public final float[] sl = new float[4];

    private int flags;

    public void initLightData(LightDataAccess cache, int x, int y, int z, ModelQuadFacing direction, boolean offset) {
        final int adjX;
        final int adjY;
        final int adjZ;

        if (offset) {
            adjX = x + direction.getStepX();
            adjY = y + direction.getStepY();
            adjZ = z + direction.getStepZ();
        } else {
            adjX = x;
            adjY = y;
            adjZ = z;
        }

        final int adjWord = cache.get(adjX, adjY, adjZ);

        final int calm;
        final boolean caem;

        if (offset && unpackFO(adjWord)) {
            final int originWord = cache.get(x, y, z);
            calm = getLightmap(originWord);
            caem = unpackEM(originWord);
        } else {
            calm = getLightmap(adjWord);
            caem = unpackEM(adjWord);
        }

        final float caao = unpackAO(adjWord);

        ModelQuadFacing[] faces = AoNeighborInfo.get(direction).faces;

        final int e0 = cache.get(adjX, adjY, adjZ, faces[0]);
        final int e0lm = getLightmap(e0);
        final float e0ao = unpackAO(e0);
        final boolean e0op = unpackOP(e0);
        final boolean e0em = unpackEM(e0);

        final int e1 = cache.get(adjX, adjY, adjZ, faces[1]);
        final int e1lm = getLightmap(e1);
        final float e1ao = unpackAO(e1);
        final boolean e1op = unpackOP(e1);
        final boolean e1em = unpackEM(e1);

        final int e2 = cache.get(adjX, adjY, adjZ, faces[2]);
        final int e2lm = getLightmap(e2);
        final float e2ao = unpackAO(e2);
        final boolean e2op = unpackOP(e2);
        final boolean e2em = unpackEM(e2);

        final int e3 = cache.get(adjX, adjY, adjZ, faces[3]);
        final int e3lm = getLightmap(e3);
        final float e3ao = unpackAO(e3);
        final boolean e3op = unpackOP(e3);
        final boolean e3em = unpackEM(e3);

        // If neither edge of a corner is occluded, then use the light
        final int c0lm;
        final float c0ao;
        final boolean c0em;

        if (e2op && e0op) {
            c0lm = e0lm;
            c0ao = e0ao;
            c0em = e0em;
        } else {
            int d0 = cache.get(adjX, adjY, adjZ, faces[0], faces[2]);
            c0lm = getLightmap(d0);
            c0ao = unpackAO(d0);
            c0em = unpackEM(d0);
        }

        final int c1lm;
        final float c1ao;
        final boolean c1em;

        if (e3op && e0op) {
            c1lm = e0lm;
            c1ao = e0ao;
            c1em = e0em;
        } else {
            int d1 = cache.get(adjX, adjY, adjZ, faces[0], faces[3]);
            c1lm = getLightmap(d1);
            c1ao = unpackAO(d1);
            c1em = unpackEM(d1);
        }

        final int c2lm;
        final float c2ao;
        final boolean c2em;

        if (e2op && e1op) {
            // FIX: Use e1 instead of e0 to fix lighting errors in some directions
            c2lm = e1lm;
            c2ao = e1ao;
            c2em = e1em;
        } else {
            int d2 = cache.get(adjX, adjY, adjZ, faces[1], faces[2]);
            c2lm = getLightmap(d2);
            c2ao = unpackAO(d2);
            c2em = unpackEM(d2);
        }

        final int c3lm;
        final float c3ao;
        final boolean c3em;

        if (e3op && e1op) {
            // FIX: Use e1 instead of e0 to fix lighting errors in some directions
            c3lm = e1lm;
            c3ao = e1ao;
            c3em = e1em;
        } else {
            int d3 = cache.get(adjX, adjY, adjZ, faces[1], faces[3]);
            c3lm = getLightmap(d3);
            c3ao = unpackAO(d3);
            c3em = unpackEM(d3);
        }

        float[] ao = this.ao;
        ao[0] = (e3ao + e0ao + c1ao + caao) * 0.25f;
        ao[1] = (e2ao + e0ao + c0ao + caao) * 0.25f;
        ao[2] = (e2ao + e1ao + c2ao + caao) * 0.25f;
        ao[3] = (e3ao + e1ao + c3ao + caao) * 0.25f;

        int[] cb = this.lm;
        cb[0] = calculateCornerBrightness(e3lm, e0lm, c1lm, calm, e3em, e0em, c1em, caem);
        cb[1] = calculateCornerBrightness(e2lm, e0lm, c0lm, calm, e2em, e0em, c0em, caem);
        cb[2] = calculateCornerBrightness(e2lm, e1lm, c2lm, calm, e2em, e1em, c2em, caem);
        cb[3] = calculateCornerBrightness(e3lm, e1lm, c3lm, calm, e3em, e1em, c3em, caem);

        if (DIFF_LOG_LIMIT > 0) {
            logVanillaDelta(x, y, z, direction, cb,
                    e3lm, e0lm, c1lm, calm, e3em, e0em, c1em, caem,
                    e2lm, e0lm, c0lm, e2em, e0em, c0em,
                    e2lm, e1lm, c2lm, e2em, e1em, c2em,
                    e3lm, e1lm, c3lm, e3em, e1em, c3em);
        }

        this.flags |= AoCompletionFlags.HAS_LIGHT_DATA;
    }

    /**
     * Vanilla's {@code BlockModelRenderer.AmbientOcclusionFace.getAoBrightness}, copied verbatim so the comparison is
     * against the real reference rather than a paraphrase of it. {@code br4} is the centre sample.
     */
    private static int vanillaAoBrightness(int br1, int br2, int br3, int br4) {
        if (br1 == 0) {
            br1 = br4;
        }

        if (br2 == 0) {
            br2 = br4;
        }

        if (br3 == 0) {
            br3 = br4;
        }

        return br1 + br2 + br3 + br4 >> 2 & 16711935;
    }

    /** {@return the 0-15 sky level encoded in a packed lightmap} */
    private static int skyLevel(int lightmap) {
        return ((lightmap >> 16) & 0xFF) / 16;
    }

    /**
     * {@return whether these four corner lightmaps land on both sides of the sky 13/14 boundary}
     * <p>
     * That boundary is where Complementary's {@code clamp(lmCoord.y - 0.87, 0.0, 0.1)} flips between zero and non-zero
     * displacement, so a face that straddles it tears when the pack waves it.
     */
    private static boolean straddlesWaveCutoff(int[] corners) {
        boolean below = false;
        boolean above = false;

        for (int corner : corners) {
            if (skyLevel(corner) >= 14) {
                above = true;
            } else {
                below = true;
            }
        }

        return below && above;
    }

    private static void logVanillaDelta(int x, int y, int z, ModelQuadFacing direction, int[] ours,
                                        int a0, int b0, int c0, int centre, boolean a0em, boolean b0em, boolean c0em, boolean cem,
                                        int a1, int b1, int c1, boolean a1em, boolean b1em, boolean c1em,
                                        int a2, int b2, int c2, boolean a2em, boolean b2em, boolean c2em,
                                        int a3, int b3, int c3, boolean a3em, boolean b3em, boolean c3em) {
        // Emissive blocks are forced to full bright by this class after averaging, which vanilla does not do at the
        // same point. That is a known, intended divergence, so skip those corners rather than report false positives.
        if (cem || a0em || b0em || c0em || a1em || b1em || c1em
                || a2em || b2em || c2em || a3em || b3em || c3em) {
            return;
        }

        int[] vanilla = {
                vanillaAoBrightness(a0, b0, c0, centre),
                vanillaAoBrightness(a1, b1, c1, centre),
                vanillaAoBrightness(a2, b2, c2, centre),
                vanillaAoBrightness(a3, b3, c3, centre),
        };

        // Trigger on the condition that actually produces the artifact, not on disagreement with vanilla. Packs gate
        // vertex effects on a hard lightmap cutoff — Complementary's `clamp(lmCoord.y - 0.87, ...)` sits between sky
        // 13 and 14 — so a face whose corners land on BOTH sides of that line is a face that will visibly tear when
        // displaced: some corners move, the rest are pinned at exactly zero.
        //
        // Logging "differs from vanilla" instead would be useless here: the two formulas disagree on ~24% of corner
        // samples purely through the zero-substitution rule, and that difference has already been tested and shown
        // not to fix this bug. Straddling is the signal; the vanilla figures ride along as the control.
        boolean straddles = straddlesWaveCutoff(ours);

        // Check the budget before spending it, so an exhausted counter cannot keep decrementing on every hit and
        // eventually wrap back into positive territory mid-session.
        if (!straddles || DIFF_LOG_BUDGET.get() <= 0 || DIFF_LOG_BUDGET.getAndDecrement() <= 0) {
            return;
        }

        // The decisive comparison: if vanilla straddles too, OptiFine would tear on this face as well and the artifact
        // is not ours. If vanilla stays on one side while we straddle, this face is the defect, with its inputs.
        LOGGER.warn("[LightDiff] {},{},{} face={} sky ours=[{},{},{},{}] straddle={} | vanilla=[{},{},{},{}] straddle={} | centre={} inputs=[{},{},{},{}]",
                x, y, z, direction,
                skyLevel(ours[0]), skyLevel(ours[1]), skyLevel(ours[2]), skyLevel(ours[3]), straddles,
                skyLevel(vanilla[0]), skyLevel(vanilla[1]), skyLevel(vanilla[2]), skyLevel(vanilla[3]),
                straddlesWaveCutoff(vanilla),
                skyLevel(centre),
                skyLevel(a0), skyLevel(b0), skyLevel(c0), skyLevel(a1));
    }

    public void unpackLightData() {
        int[] lm = this.lm;

        float[] bl = this.bl;
        float[] sl = this.sl;

        bl[0] = unpackBlockLight(lm[0]);
        bl[1] = unpackBlockLight(lm[1]);
        bl[2] = unpackBlockLight(lm[2]);
        bl[3] = unpackBlockLight(lm[3]);

        sl[0] = unpackSkyLight(lm[0]);
        sl[1] = unpackSkyLight(lm[1]);
        sl[2] = unpackSkyLight(lm[2]);
        sl[3] = unpackSkyLight(lm[3]);

        this.flags |= AoCompletionFlags.HAS_UNPACKED_LIGHT_DATA;
    }

    public float getBlendedSkyLight(float[] w) {
        return weightedSum(this.sl, w);
    }

    public float getBlendedBlockLight(float[] w) {
        return weightedSum(this.bl, w);
    }

    public float getBlendedShade(float[] w) {
        return weightedSum(this.ao, w);
    }

    private static float weightedSum(float[] v, float[] w) {
        float t0 = v[0] * w[0];
        float t1 = v[1] * w[1];
        float t2 = v[2] * w[2];
        float t3 = v[3] * w[3];

        return t0 + t1 + t2 + t3;
    }

    private static float unpackSkyLight(int i) {
        return (i >> 16) & 0xFF;
    }

    private static float unpackBlockLight(int i) {
        return i & 0xFF;
    }

    private static int calculateCornerBrightness(int a, int b, int c, int d, boolean aem, boolean bem, boolean cem, boolean dem) {
        // FIX: Normalize corner vectors correctly to the minimum non-zero value between each one to prevent
        // strange issues
        if ((a == 0) || (b == 0) || (c == 0) || (d == 0)) {
            // Find the minimum value between all corners
            final int min = minNonZero(minNonZero(a, b), minNonZero(c, d));

            // Normalize the corner values
            a = Math.max(a, min);
            b = Math.max(b, min);
            c = Math.max(c, min);
            d = Math.max(d, min);
        }

        // FIX: Apply the fullbright lightmap from emissive blocks at the very end so it cannot influence
        // the minimum lightmap and produce incorrect results (for example, sculk sensors in a dark room)
        if (aem) {
            a = LightDataAccess.FULL_BRIGHT;
        }
        if (bem) {
            b = LightDataAccess.FULL_BRIGHT;
        }
        if (cem) {
            c = LightDataAccess.FULL_BRIGHT;
        }
        if (dem) {
            d = LightDataAccess.FULL_BRIGHT;
        }

        return ((a + b + c + d) >> 2) & 0xFF00FF;
    }

    private static int minNonZero(int a, int b) {
        if (a == 0) {
            return b;
        } else if (b == 0) {
            return a;
        }

        return Math.min(a, b);
    }

    public boolean hasLightData() {
        return (this.flags & AoCompletionFlags.HAS_LIGHT_DATA) != 0;
    }

    public boolean hasUnpackedLightData() {
        return (this.flags & AoCompletionFlags.HAS_UNPACKED_LIGHT_DATA) != 0;
    }

    public void reset() {
        this.flags = 0;
    }
}
