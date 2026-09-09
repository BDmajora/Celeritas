package com.bdmajora.impetus.umbra.vertices;

// The extra per-vertex attributes an OptiFine 1.12.2 pack expects, and the fixed attribute slots OptiFine
// hard-codes for them
// The slots are bound at program-link time by ShaderProgramCompiler and must match what the terrain and entity
// vertex encoders actually write — a mismatch feeds a pack tangents where it expects entity ids
// Position, colour, UV and normal go through the fixed-function slots; only the extended data needs dedicated
// generic slots, which is why only three names live here
// overlayId does not exist on 1.12.2 and is deliberately absent rather than stubbed
public final class UmbraVertexAttributes {
    // Block id in x and metadata in y, both derived from the IBlockState
    public static final String MC_ENTITY = "mc_Entity";
    public static final int MC_ENTITY_SLOT = 10;

    // The centre UV of the texture region this QUAD maps, the mean of its four vertex UVs
    // Packs rebuild an atlas-local coordinate basis from it, which is what makes their texcoord maths survive
    // sprite animation moving the region around the atlas
    public static final String MC_MID_TEX_COORD = "mc_midTexCoord";
    public static final int MC_MID_TEX_COORD_SLOT = 11;

    // Tangent in xyz and bitangent handedness in w, derived from the face normal and the UV orientation
    public static final String AT_TANGENT = "at_tangent";
    public static final int AT_TANGENT_SLOT = 12;

    private UmbraVertexAttributes() {
    }
}
