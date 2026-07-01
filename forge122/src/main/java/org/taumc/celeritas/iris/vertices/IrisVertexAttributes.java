package org.taumc.celeritas.iris.vertices;

/**
 * The extra per-vertex attributes an OptiFine 1.12.2 shader pack expects, with the fixed attribute-location slots
 * OptiFine hard-codes for them. These slots are bound at program-link time (see
 * {@link org.taumc.celeritas.iris.gl.program.ShaderProgramCompiler}) and must match the layout the terrain/entity
 * vertex encoders write (Phase 2/5).
 * <p>
 * OptiFine uses fixed-function slots for position/color/uv/normal and dedicated generic attribute slots 10–12 for the
 * extended data. {@code overlayId} does not exist in 1.12.2 and is intentionally absent.
 */
public final class IrisVertexAttributes {
    /** {@code vec2} — block id (x) and block metadata (y), from {@code IBlockState}. */
    public static final String MC_ENTITY = "mc_Entity";
    public static final int MC_ENTITY_SLOT = 10;

    /** {@code vec2} — the center UV of the quad's {@code TextureAtlasSprite} (for animation-safe texcoord math). */
    public static final String MC_MID_TEX_COORD = "mc_midTexCoord";
    public static final int MC_MID_TEX_COORD_SLOT = 11;

    /** {@code vec4} — tangent vector (xyz) and handedness (w), derived from the face normal + UV orientation. */
    public static final String AT_TANGENT = "at_tangent";
    public static final int AT_TANGENT_SLOT = 12;

    private IrisVertexAttributes() {
    }
}
