package com.bdmajora.impetus.umbra.vertices;

// The extended per-vertex attributes a pack expects and the slots OptiFine hard-codes for them, bound at link time and matched by the encoders (a mismatch feeds tangents where entity ids are expected); overlayId does not exist on 1.12.2
public final class UmbraVertexAttributes {
    // Block id in x and metadata in y, both derived from the IBlockState
    public static final String MC_ENTITY = "mc_Entity";
    public static final int MC_ENTITY_SLOT = 10;

    // The centre UV of the texture region this QUAD maps, the mean of its four vertex UVs; packs rebuild an atlas-local basis from it so their texcoord maths survives sprite animation
    public static final String MC_MID_TEX_COORD = "mc_midTexCoord";
    public static final int MC_MID_TEX_COORD_SLOT = 11;

    // Tangent in xyz and bitangent handedness in w, derived from the face normal and the UV orientation
    public static final String AT_TANGENT = "at_tangent";
    public static final int AT_TANGENT_SLOT = 12;

    private UmbraVertexAttributes() {
    }
}
