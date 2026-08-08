package com.bdmajora.impetus.iris.shaderpack.loading;

import java.util.Locale;

/**
 * The set of shader programs that an OptiFine-style 1.12.2 shader pack may declare.
 * <p>
 * Unlike modern Iris (which exposes the {@code rendertype_*} programs), 1.12.2 packs only ever use the classic
 * OptiFine {@code gbuffers_*}, {@code shadow*}, {@code deferred*}, {@code composite*} and {@code final} program
 * families. The numbered families (deferred, composite, shadowcomp) are represented by {@link ProgramArrayId}.
 * <p>
 * A {@code ProgramId} simply names a {@code .vsh}/{@code .gsh}/{@code .fsh} triple that may live in the pack's
 * {@code shaders/} directory. Whether a given program is actually present is decided at load time.
 */
public enum ProgramId {
    // --- "Basic"/sky/textured family ---
    Basic("gbuffers_basic"),
    Line("gbuffers_line", Basic),
    Textured("gbuffers_textured", Basic),
    TexturedLit("gbuffers_textured_lit", Textured),
    SkyBasic("gbuffers_skybasic", Basic),
    SkyTextured("gbuffers_skytextured", Textured),
    Clouds("gbuffers_clouds", Textured),

    // --- Terrain family ---
    Terrain("gbuffers_terrain", TexturedLit),
    TerrainSolid("gbuffers_terrain_solid", Terrain),
    TerrainCutout("gbuffers_terrain_cutout", Terrain),
    TerrainCutoutMip("gbuffers_terrain_cutout_mip", Terrain),
    DamagedBlock("gbuffers_damagedblock", Terrain),
    Block("gbuffers_block", Terrain),
    BeaconBeam("gbuffers_beaconbeam", Textured),
    Item("gbuffers_item", TexturedLit),

    // --- Entity family ---
    Entities("gbuffers_entities", TexturedLit),
    EntitiesTrans("gbuffers_entities_translucent", Entities),
    EntitiesGlowing("gbuffers_entities_glowing", Entities),
    ArmorGlint("gbuffers_armor_glint", Textured),
    SpiderEyes("gbuffers_spidereyes", Textured),
    Hand("gbuffers_hand", TexturedLit),
    Weather("gbuffers_weather", TexturedLit),

    // --- Water / translucent family ---
    Water("gbuffers_water", Terrain),
    HandWater("gbuffers_hand_water", Hand),

    // --- Single shadow program (1.12.2 OptiFine convention is one ortho shadow map) ---
    Shadow("shadow"),

    // --- Single composite/deferred/final entries (numbered variants come from ProgramArrayId) ---
    Final("final");

    private final String sourceName;
    private final ProgramId fallback;

    ProgramId(String sourceName) {
        this(sourceName, null);
    }

    ProgramId(String sourceName, ProgramId fallback) {
        this.sourceName = sourceName;
        this.fallback = fallback;
    }

    /**
     * @return the base file name (without extension) used inside {@code shaders/}, e.g. {@code gbuffers_terrain}.
     */
    public String getSourceName() {
        return this.sourceName;
    }

    /**
     * OptiFine defines a fallback chain: if a pack doesn't supply {@code gbuffers_terrain} it falls back to
     * {@code gbuffers_textured_lit}, then {@code gbuffers_textured}, then {@code gbuffers_basic}.
     *
     * @return the program to use when this one is absent, or {@code null} if there is no fallback.
     */
    public ProgramId getFallback() {
        return this.fallback;
    }

    public static ProgramId bySourceName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (ProgramId id : values()) {
            if (id.sourceName.equals(lower)) {
                return id;
            }
        }
        return null;
    }
}
