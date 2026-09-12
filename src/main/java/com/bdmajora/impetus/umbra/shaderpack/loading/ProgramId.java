package com.bdmajora.impetus.umbra.shaderpack.loading;

import com.bdmajora.impetus.umbra.gl.blending.BlendMode;
import com.bdmajora.impetus.lwjgl.GL11;

import java.util.Locale;

// Every program a 1.12.2 pack may declare: the classic gbuffers_*, shadow* and final families
// Numbered families live in ProgramArrayId; an id only names a triple that may exist, presence is decided at load
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
    // OptiFine packs call that program gbuffers_terrain_cutout_mip, Umbra-era packs only ship gbuffers_terrain_cutout.
    TerrainCutoutMip("gbuffers_terrain_cutout_mip", TerrainCutout),
    DamagedBlock("gbuffers_damagedblock", Terrain),
    Block("gbuffers_block", Terrain),
    // The translucent half of the block-entity program, matching Iris's ProgramId.BlockTrans
    BlockTrans("gbuffers_block_translucent", Block),
    BeaconBeam("gbuffers_beaconbeam", Textured),
    Item("gbuffers_item", TexturedLit),

    // --- Entity family ---
    Entities("gbuffers_entities", TexturedLit),
    EntitiesTrans("gbuffers_entities_translucent", Entities),
    EntitiesGlowing("gbuffers_entities_glowing", Entities),
    // Lightning bolts, split out of the entity program so a pack can shade them differently — they are emissive
    // geometry that would otherwise be lit like a mob
    Lightning("gbuffers_lightning", Entities),
    Particles("gbuffers_particles", TexturedLit),
    ParticlesTrans("gbuffers_particles_translucent", Particles),
    ArmorGlint("gbuffers_armor_glint", Textured),
    // The "eyes" overlay layers — spider, enderman, ender dragon
    // Carries a DEFAULT blend override, premultiplied additive with destination alpha untouched, standing in for
    // vanilla's plain ONE, ONE
    // The default exists because a pack commonly ships gbuffers_spidereyes without a matching
    // blend.gbuffers_spidereyes directive, and without it the eyes render opaque black over the mob
    SpiderEyes("gbuffers_spidereyes", Textured,
            new BlendMode(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE)),
    Hand("gbuffers_hand", TexturedLit),
    Weather("gbuffers_weather", TexturedLit),

    // --- Water / translucent family ---
    Water("gbuffers_water", Terrain),
    HandWater("gbuffers_hand_water", Hand),

    // --- Shadow family ---
    // Umbra exposes a whole ProgramGroup.Shadow, and OptiFine ships shadow_solid/shadow_cutout too (program table
    // indices 31/32). Every one of these falls back to plain `shadow`, so a pack that declares none behaves exactly
    // as before: `ProgramSet#get` walks the chain and lands on the same source it would have used anyway. Packs that
    // DO ship them — to skip alpha-testing on solid shadow geometry, or to treat entities differently in the shadow
    // map — previously had those files silently ignored.
    Shadow("shadow"),
    ShadowSolid("shadow_solid", Shadow),
    ShadowCutout("shadow_cutout", Shadow),
    ShadowWater("shadow_water", Shadow),
    ShadowEntities("shadow_entities", Shadow),
    // Falls back to shadow_entities rather than plain shadow, matching Iris — so a pack that overrides entity
    // shadows gets that override applied to lightning too, instead of lightning silently using the generic program
    ShadowLightning("shadow_lightning", ShadowEntities),
    ShadowBlock("shadow_block", Shadow),

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

    // The base file name inside shaders/, without extension — e.g. gbuffers_terrain, which the loader then looks
    // for as .vsh, .gsh and .fsh
    public String getSourceName() {
        return this.sourceName;
    }

    // The program to use when this one is absent, or null at the end of the chain
    // OptiFine's fallback chain is what lets a three-file pack shade the whole world: a missing gbuffers_terrain
    // resolves to gbuffers_textured_lit, then gbuffers_textured, then gbuffers_basic
    public ProgramId getFallback() {
        return this.fallback;
    }

    // The blend mode this program gets when the pack declared no blend.<program> of its own, or null when it has
    // no default
    // Only meaningful for a DIRECTLY declared program. Resolving through the fallback chain lands on a different
    // program's source, and that source keeps its own (absent) blend directives rather than inheriting this one —
    // same rule Iris follows
    public BlendMode getDefaultBlendMode() {
        return this.defaultBlendMode;
    }

    // File base name to id; null when not a known program
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
