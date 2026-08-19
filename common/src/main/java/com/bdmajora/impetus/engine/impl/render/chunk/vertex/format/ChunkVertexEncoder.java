package com.bdmajora.impetus.engine.impl.render.chunk.vertex.format;

import com.bdmajora.impetus.engine.impl.render.chunk.terrain.material.Material;

public interface ChunkVertexEncoder {
    long write(long ptr, Material material, Vertex vertex, int sectionIndex);

    class Vertex {
        public float x;
        public float y;
        public float z;
        public int color;
        public float u;
        public float v;
        public int light;
        /**
         * The normal that vanilla would output for this quad. Unused by Impetus's built-in shaders, but might be used
         * by a core shader.
         */
        public int vanillaNormal;
        /**
         * The actual normal vector of this quad computed off the geometry.
         */
        public int trueNormal;

        // ---- Iris (Impetus shader pipeline) extended per-vertex data ----
        // These are only read by an Iris-extended ChunkVertexType/encoder; the default encoders ignore them, so they
        // are inert (and cost nothing) unless a shader pack is active. Populated by the meshing pipeline only when
        // Iris is in use. See com.bdmajora.impetus.iris.vertices.

        /**
         * {@code mc_midTexCoord.x} — U of the centre of the texture region mapped to this QUAD (the mean of the
         * four vertex Us), matching Iris. NOT the sprite centre: the two agree only for full-sprite quads, and
         * using the sprite wrecks the atlas basis Chocapic-derived packs rebuild from this attribute on any face
         * that maps a sub-rect (vanilla torch cap faces being the worst case).
         */
        public float midTexU;
        /** {@code mc_midTexCoord.y} — V of the quad's texture centre (the mean of the four vertex Vs). */
        public float midTexV;
        /** {@code mc_Entity.x} - shader-facing block id: block.properties id when mapped, raw block id otherwise. */
        public int blockId;
        /** {@code mc_Entity.y} - 1.12.2 OptiFine render type ordinal. */
        public int blockRenderType;
        /** {@code mc_Entity.z} - 1.12.2 block metadata ({@code Block.getMetaFromState}). */
        public int blockData;
        /** {@code at_tangent} — packed tangent (xyz signed bytes + handedness), same packing as a normal. */
        public int tangent;
        /**
         * {@code at_midBlock} — offset from this vertex to the CENTER of the block it belongs to, in block units
         * (range roughly -0.5..0.5 for a full block). Iris/OptiFine shaders read {@code at_midBlock.xyz / 64.0}, so the
         * encoder scales these by 64 into signed bytes. Colored-lighting voxelization depends on this: sampling at the
         * block center (an X.5,Y.5,Z.5 world position) keeps the voxel-grid lookup off cell boundaries, where it would
         * otherwise flip between cells under sub-voxel precision noise and make the light volume shimmer.
         */
        public float midBlockX;
        public float midBlockY;
        public float midBlockZ;
        /** {@code at_midBlock.w} — block light emission, matching Iris' Sodium terrain extension. */
        public int blockEmission;

        public static Vertex[] uninitializedQuad() {
            Vertex[] vertices = new Vertex[4];

            for (int i = 0; i < 4; i++) {
                vertices[i] = new Vertex();
            }

            return vertices;
        }

        @Override
        public String toString() {
            return String.format("XYZ: (%.02f, %.02f, %.02f), C: %08x, L: %08x", x, y, z, color, light);
        }
    }
}
