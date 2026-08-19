package com.bdmajora.impetus.iris.shaderpack.loading;

import com.bdmajora.impetus.iris.gl.blending.BlendMode;
import com.bdmajora.impetus.lwjgl.GL11;

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
    // Impetus draws cutout and cutout-mipped as a single pass, so this id has to satisfy both naming conventions:
    // OptiFine packs call that program gbuffers_terrain_cutout_mip, Iris-era packs only ship gbuffers_terrain_cutout.
    TerrainCutoutMip("gbuffers_terrain_cutout_mip", TerrainCutout),
    DamagedBlock("gbuffers_damagedblock", Terrain),
    Block("gbuffers_block", Terrain),
    BeaconBeam("gbuffers_beaconbeam", Textured),
    Item("gbuffers_item", TexturedLit),

    // --- Entity family ---
    Entities("gbuffers_entities", TexturedLit),
    EntitiesTrans("gbuffers_entities_translucent", Entities),
    EntitiesGlowing("gbuffers_entities_glowing", Entities),
    Particles("gbuffers_particles", TexturedLit),
    ParticlesTrans("gbuffers_particles_translucent", Particles),
    ArmorGlint("gbuffers_armor_glint", Textured),
    /**
     * The "eyes" overlay layers (spider, enderman, ender dragon). Iris gives this program a default blend override
     * (premultiplied additive, destination alpha untouched) that stands in for vanilla's plain {@code ONE, ONE}, so a
     * pack that ships the program but no {@code blend.gbuffers_spidereyes} directive still gets it.
     */
    SpiderEyes("gbuffers_spidereyes", Textured,
            new BlendMode(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE)),
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
    private final BlendMode defaultBlendMode;

    ProgramId(String sourceName) {
        this(sourceName, null, null);
    }

    ProgramId(String sourceName, ProgramId fallback) {
        this(sourceName, fallback, null);
    }

    ProgramId(String sourceName, ProgramId fallback, BlendMode defaultBlendMode) {
        this.sourceName = sourceName;
        this.fallback = fallback;
        this.defaultBlendMode = defaultBlendMode;
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

    /**
     * The blend mode this program gets when the pack declared no {@code blend.<program>} of its own. Only meaningful
     * for a directly-declared program: resolving through {@link #getFallback()} lands on another program's source,
     * and that source keeps its own (absent) blend directives, exactly as in Iris.
     *
     * @return the default, or {@code null} when the program has none.
     */
    public BlendMode getDefaultBlendMode() {
        return this.defaultBlendMode;
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
