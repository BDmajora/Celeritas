package com.bdmajora.impetus.engine.impl.render.chunk.vertex.format;

import com.bdmajora.impetus.engine.impl.render.chunk.terrain.material.Material;

// Writes one terrain vertex into a native buffer in whatever layout the active ChunkVertexType declares
// The engine ships a compact default encoder; Umbra swaps in its own when a shader pack is loaded, which is why
// Vertex below carries fields the default encoder never looks at
public interface ChunkVertexEncoder {
    // Writes the vertex at ptr and returns the pointer just past it, so the caller advances by striding rather
    // than by recomputing an offset per vertex
    // sectionIndex identifies the chunk section this vertex belongs to, for formats that pack a section-relative
    // origin instead of absolute coordinates
    long write(long ptr, Material material, Vertex vertex, int sectionIndex);

    // A single vertex in flight, mutable and reused
    // The mesher fills one of these per corner, hands it to the encoder, and immediately overwrites it for the
    // next corner — so nothing may hold on to a Vertex past the write call
    class Vertex {
        public float x;
        public float y;
        public float z;
        public int color;
        public float u;
        public float v;
        public int light;
        // The normal vanilla would have emitted for this quad — the face direction, snapped to an axis
        // Impetus's built-in shaders never read it, but a core shader replacing them might, so it is still packed
        public int vanillaNormal;
        // The quad's real normal, computed from the geometry rather than assumed from the face
        // Differs from vanillaNormal on anything non-axis-aligned, which is what normal mapping needs
        public int trueNormal;

        // ---- Umbra (Impetus shader pipeline) extended per-vertex data ----
        // Everything below is read only by an Umbra-extended ChunkVertexType and its encoder. The default engine
        // encoders never touch these fields, so they cost nothing but the (reused) object's footprint when no
        // shader pack is loaded, and the mesher only bothers computing them while Umbra is in use.
        // The comments name the GLSL attribute each one ends up as; see com.bdmajora.impetus.umbra.vertices.

        // mc_midTexCoord.x — U of the centre of the texture region this QUAD maps, i.e. the mean of the four
        // vertex Us, matching Iris
        // Deliberately NOT the sprite centre. The two agree only when the quad maps a whole sprite, and using the
        // sprite instead breaks the atlas basis that Chocapic-derived packs reconstruct from this attribute on
        // every face mapping a sub-rect — vanilla torch cap faces are the worst offender
        public float midTexU;
        // mc_midTexCoord.y — the same for V, the mean of the four vertex Vs
        public float midTexV;
        // mc_Entity.x — the block id the shader sees: the pack's block.properties id when block.properties mapped
        // this state, otherwise the raw block id
        public int blockId;
        // mc_Entity.y — the 1.12.2 OptiFine render type ordinal for this block
        public int blockRenderType;
        // mc_Entity.z — 1.12.2 block metadata, i.e. Block.getMetaFromState
        public int blockData;
        // at_tangent — packed tangent: xyz as signed bytes plus handedness in w, the same packing as a normal
        public int tangent;
        // at_midBlock.xyz — offset from this vertex to the CENTRE of its block, in block units, so roughly
        // -0.5..0.5 for a full block
        // Shaders read at_midBlock.xyz / 64.0, so the encoder multiplies by 64 and stores signed bytes
        // Colored-lighting voxelization leans on this: adding the offset lands the sample on the block centre, an
        // X.5/Y.5/Z.5 world position, which keeps the voxel-grid lookup away from cell boundaries. On a boundary
        // sub-voxel precision noise flips the lookup between neighbouring cells and the light volume shimmers
        public float midBlockX;
        public float midBlockY;
        public float midBlockZ;
        // at_midBlock.w — this block's light emission level, matching Iris's Sodium terrain extension
        public int blockEmission;

        // Four blank vertices for one quad, allocated once and then refilled per quad by the mesher
        // "Uninitialized" is literal: every field is default-valued and the caller is expected to set all of them
        public static Vertex[] uninitializedQuad() {
            Vertex[] vertices = new Vertex[4];

            for (int i = 0; i < 4; i++) {
                vertices[i] = new Vertex();
            }

            return vertices;
        }

        // Debug only — position, packed colour and packed light, which is enough to identify a bad vertex in a
        // breakpoint without printing the shader-only fields that are usually zero
        @Override
        public String toString() {
            return String.format("XYZ: (%.02f, %.02f, %.02f), C: %08x, L: %08x", x, y, z, color, light);
        }
    }
}
