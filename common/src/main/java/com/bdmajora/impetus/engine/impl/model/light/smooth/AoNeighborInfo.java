package com.bdmajora.impetus.engine.impl.model.light.smooth;

import com.bdmajora.impetus.engine.impl.model.quad.properties.ModelQuadFacing;

// the neighbour information for each face of a block, used during smooth lighting to calculate the
// occlusion of each corner
@SuppressWarnings("UnnecessaryLocalVariable")
enum AoNeighborInfo {
    POS_X(new ModelQuadFacing[] { ModelQuadFacing.NEG_Y, ModelQuadFacing.POS_Y, ModelQuadFacing.NEG_Z, ModelQuadFacing.POS_Z }, 0.6F) {
        @Override
        public void calculateCornerWeights(float x, float y, float z, float[] out) {
            final float u = z;
            final float v = 1.0f - y;

            out[0] = v * u;
            out[1] = v * (1.0f - u);
            out[2] = (1.0f - v) * (1.0f - u);
            out[3] = (1.0f - v) * u;
        }

        @Override
        public void mapCorners(int[] lm0, float[] ao0, int[] lm1, float[] ao1) {
            lm1[1] = lm0[0];
            lm1[2] = lm0[1];
            lm1[3] = lm0[2];
            lm1[0] = lm0[3];

            ao1[1] = ao0[0];
            ao1[2] = ao0[1];
            ao1[3] = ao0[2];
            ao1[0] = ao0[3];
        }

        @Override
        public float getDepth(float x, float y, float z) {
            return 1.0f - x;
        }
    },
    POS_Y(new ModelQuadFacing[] { ModelQuadFacing.POS_X, ModelQuadFacing.NEG_X, ModelQuadFacing.NEG_Z, ModelQuadFacing.POS_Z }, 1.0F) {
        @Override
        public void calculateCornerWeights(float x, float y, float z, float[] out) {
            final float u = z;
            final float v = x;

            out[0] = v * u;
            out[1] = v * (1.0f - u);
            out[2] = (1.0f - v) * (1.0f - u);
            out[3] = (1.0f - v) * u;
        }

        @Override
        public void mapCorners(int[] lm0, float[] ao0, int[] lm1, float[] ao1) {
            lm1[2] = lm0[0];
            lm1[3] = lm0[1];
            lm1[0] = lm0[2];
            lm1[1] = lm0[3];

            ao1[2] = ao0[0];
            ao1[3] = ao0[1];
            ao1[0] = ao0[2];
            ao1[1] = ao0[3];
        }

        @Override
        public float getDepth(float x, float y, float z) {
            return 1.0f - y;
        }
    },
    POS_Z(new ModelQuadFacing[] { ModelQuadFacing.NEG_X, ModelQuadFacing.POS_X, ModelQuadFacing.NEG_Y, ModelQuadFacing.POS_Y }, 0.8F) {
        @Override
        public void calculateCornerWeights(float x, float y, float z, float[] out) {
            final float u = y;
            final float v = 1.0f - x;

            out[0] = u * v;
            out[1] = (1.0f - u) * v;
            out[2] = (1.0f - u) * (1.0f - v);
            out[3] = u * (1.0f - v);
        }

        @Override
        public void mapCorners(int[] lm0, float[] ao0, int[] lm1, float[] ao1) {
            lm1[0] = lm0[0];
            lm1[1] = lm0[1];
            lm1[2] = lm0[2];
            lm1[3] = lm0[3];

            ao1[0] = ao0[0];
            ao1[1] = ao0[1];
            ao1[2] = ao0[2];
            ao1[3] = ao0[3];
        }

        @Override
        public float getDepth(float x, float y, float z) {
            return 1.0f - z;
        }
    },
    NEG_X(new ModelQuadFacing[] { ModelQuadFacing.POS_Y, ModelQuadFacing.NEG_Y, ModelQuadFacing.NEG_Z, ModelQuadFacing.POS_Z }, 0.6F) {
        @Override
        public void calculateCornerWeights(float x, float y, float z, float[] out) {
            final float u = z;
            final float v = y;

            out[0] = v * u;
            out[1] = v * (1.0f - u);
            out[2] = (1.0f - v) * (1.0f - u);
            out[3] = (1.0f - v) * u;
        }

        @Override
        public void mapCorners(int[] lm0, float[] ao0, int[] lm1, float[] ao1) {
            lm1[3] = lm0[0];
            lm1[0] = lm0[1];
            lm1[1] = lm0[2];
            lm1[2] = lm0[3];

            ao1[3] = ao0[0];
            ao1[0] = ao0[1];
            ao1[1] = ao0[2];
            ao1[2] = ao0[3];
        }

        @Override
        public float getDepth(float x, float y, float z) {
            return x;
        }
    },
    NEG_Y(new ModelQuadFacing[] { ModelQuadFacing.NEG_X, ModelQuadFacing.POS_X, ModelQuadFacing.NEG_Z, ModelQuadFacing.POS_Z }, 0.5F) {
        @Override
        public void calculateCornerWeights(float x, float y, float z, float[] out) {
            final float u = z;
            final float v = 1.0f - x;

            out[0] = v * u;
            out[1] = v * (1.0f - u);
            out[2] = (1.0f - v) * (1.0f - u);
            out[3] = (1.0f - v) * u;
        }

        @Override
        public void mapCorners(int[] lm0, float[] ao0, int[] lm1, float[] ao1) {
            lm1[0] = lm0[0];
            lm1[1] = lm0[1];
            lm1[2] = lm0[2];
            lm1[3] = lm0[3];

            ao1[0] = ao0[0];
            ao1[1] = ao0[1];
            ao1[2] = ao0[2];
            ao1[3] = ao0[3];
        }

        @Override
        public float getDepth(float x, float y, float z) {
            return y;
        }
    },
    NEG_Z(new ModelQuadFacing[] { ModelQuadFacing.POS_Y, ModelQuadFacing.NEG_Y, ModelQuadFacing.POS_X, ModelQuadFacing.NEG_X }, 0.8F) {
        @Override
        public void calculateCornerWeights(float x, float y, float z, float[] out) {
            final float u = 1.0f - x;
            final float v = y;

            out[0] = v * u;
            out[1] = v * (1.0f - u);
            out[2] = (1.0f - v) * (1.0f - u);
            out[3] = (1.0f - v) * u;
        }

        @Override
        public void mapCorners(int[] lm0, float[] ao0, int[] lm1, float[] ao1) {
            lm1[3] = lm0[0];
            lm1[0] = lm0[1];
            lm1[1] = lm0[2];
            lm1[2] = lm0[3];

            ao1[3] = ao0[0];
            ao1[0] = ao0[1];
            ao1[1] = ao0[2];
            ao1[2] = ao0[3];
        }

        @Override
        public float getDepth(float x, float y, float z) {
            return z;
        }
    };


    private static final AoNeighborInfo[] VALUES = AoNeighborInfo.values();
    // the direction of each corner block from this face, reached by offsetting the origin block's
    // position by the direction vector
    public final ModelQuadFacing[] faces;
    // the constant brightness modifier for this face, emulating the OpenGL lighting model that gives
    // blocks their faux directional-light appearance; not currently used
    public final float strength;

    AoNeighborInfo(ModelQuadFacing[] directions, float strength) {
        this.faces = directions;
        this.strength = strength;
    }

    // the AoNeighborInfo corresponding to the given direction
    public static AoNeighborInfo get(ModelQuadFacing direction) {
        if (!direction.isDirection()) {
            throw new IllegalArgumentException();
        }
        return VALUES[direction.ordinal()];
    }

    // calculates how much each corner contributes to the final "darkening" of the vertex at this
    // position; the weight is a function of the distance from the vertex to the corner block
    // x/y/z are the vertex position, out receives the weight for each corner
    public abstract void calculateCornerWeights(float x, float y, float z, float[] out);

    // maps the light map array lm0 and the occlusion array ao0 from AoFaceData onto the correct corners
    // for this facing
    // lm0/ao0 are the inputs, lm1/ao1 the re-oriented outputs
    public abstract void mapCorners(int[] lm0, float[] ao0, int[] lm1, float[] ao1);

    // the depth (or inset) of the vertex into this facing of the block, used to decide how much shadow
    // is contributed by the block's direct neighbours
    // x/y/z are the vertex position
    public abstract float getDepth(float x, float y, float z);
}
