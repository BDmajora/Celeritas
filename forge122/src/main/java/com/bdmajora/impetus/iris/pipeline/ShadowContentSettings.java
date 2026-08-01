package com.bdmajora.impetus.iris.pipeline;

import com.bdmajora.impetus.iris.shaderpack.ShaderProperties;

/**
 * What the shadow pass is allowed to draw, from the {@code shadowTerrain} / {@code shadowTranslucent} /
 * {@code shadowEntities} / {@code shadowBlockEntities} / {@code shadowLightBlockEntities} / {@code shadowPlayer}
 * directives, plus {@code shadow.culling}.
 * <p>
 * Every pack in use sets several of these — Photon asks for {@code shadowEntities = false},
 * {@code shadowBlockEntities = false}, {@code shadowPlayer = true}; Complementary switches them by its entity-shadow
 * option. Ignoring them costs real frame time drawing geometry the pack does not want shadowed, and shows up
 * visually as shadows the pack deliberately omitted.
 * <p>
 * Defaults follow OptiFine: everything except {@code shadowLightBlockEntities} is drawn unless the pack says
 * otherwise.
 */
public final class ShadowContentSettings {
    /** {@code shadow.culling} — how the shadow pass culls chunks. */
    public enum Culling {
        /** Cull against the shadow frustum only. */
        ON,
        /** No culling: every chunk in range is drawn. */
        OFF,
        /**
         * Iris's {@code AdvancedShadowCullingFrustum}: keeps geometry between the light and the view frustum that a
         * plain shadow-frustum test would drop (which is what makes off-screen casters still cast).
         */
        REVERSED
    }

    private final boolean terrain;
    private final boolean translucent;
    private final boolean entities;
    private final boolean blockEntities;
    private final boolean lightBlockEntities;
    private final boolean player;
    private final Culling culling;

    private ShadowContentSettings(boolean terrain, boolean translucent, boolean entities, boolean blockEntities,
                                  boolean lightBlockEntities, boolean player, Culling culling) {
        this.terrain = terrain;
        this.translucent = translucent;
        this.entities = entities;
        this.blockEntities = blockEntities;
        this.lightBlockEntities = lightBlockEntities;
        this.player = player;
        this.culling = culling;
    }

    public static ShadowContentSettings defaults() {
        return new ShadowContentSettings(true, true, true, true, false, true, Culling.ON);
    }

    public static ShadowContentSettings from(ShaderProperties properties) {
        return new ShadowContentSettings(
                properties.getShadowTerrain().orElse(Boolean.TRUE),
                properties.getShadowTranslucent().orElse(Boolean.TRUE),
                properties.getShadowEntities().orElse(Boolean.TRUE),
                properties.getShadowBlockEntities().orElse(Boolean.TRUE),
                properties.getShadowLightBlockEntities().orElse(Boolean.FALSE),
                properties.getShadowPlayer().orElse(Boolean.TRUE),
                parseCulling(properties.getShadowCulling().orElse(null)));
    }

    private static Culling parseCulling(String value) {
        if (value == null) {
            return Culling.ON;
        }
        switch (value) {
            case "false":
            case "off":
                return Culling.OFF;
            case "reversed":
                return Culling.REVERSED;
            default:
                return Culling.ON;
        }
    }

    public boolean shouldRenderTerrain() {
        return this.terrain;
    }

    public boolean shouldRenderTranslucent() {
        return this.translucent;
    }

    public boolean shouldRenderEntities() {
        return this.entities;
    }

    /** True when any block entity should draw: either all of them, or only the light-emitting ones. */
    public boolean shouldRenderAnyBlockEntities() {
        return this.blockEntities || this.lightBlockEntities;
    }

    public boolean shouldRenderBlockEntities() {
        return this.blockEntities;
    }

    public boolean shouldRenderLightBlockEntitiesOnly() {
        return !this.blockEntities && this.lightBlockEntities;
    }

    public boolean shouldRenderPlayer() {
        return this.player;
    }

    public Culling getCulling() {
        return this.culling;
    }

    @Override
    public String toString() {
        return "terrain=" + this.terrain + ", translucent=" + this.translucent + ", entities=" + this.entities
                + ", blockEntities=" + this.blockEntities + ", lightBlockEntities=" + this.lightBlockEntities
                + ", player=" + this.player + ", culling=" + this.culling;
    }
}
