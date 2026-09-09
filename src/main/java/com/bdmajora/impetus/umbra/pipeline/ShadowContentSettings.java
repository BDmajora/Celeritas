package com.bdmajora.impetus.umbra.pipeline;

import com.bdmajora.impetus.umbra.shaderpack.ShaderProperties;

// What the shadow pass is allowed to draw, from the pack's shadowTerrain, shadowTranslucent, shadowEntities,
// shadowBlockEntities, shadowLightBlockEntities and shadowPlayer directives, plus shadow.culling
// Every pack in real use sets several of these. Photon asks for shadowEntities = false, shadowBlockEntities =
// false and shadowPlayer = true; Complementary flips them from its own entity-shadow option
// Ignoring them is not a cosmetic shortcut: it costs real frame time drawing geometry the pack does not want
// shadowed at all, and it shows up on screen as shadows the pack deliberately omitted
// Defaults follow OptiFine — everything except shadowLightBlockEntities is drawn unless the pack says otherwise
public final class ShadowContentSettings {
    // shadow.culling — how the shadow pass decides which chunks to walk
    public enum Culling {
        // Cull against the shadow frustum only
        ON,
        // No culling at all: every chunk in range is drawn
        OFF,
        // Iris's AdvancedShadowCullingFrustum: keeps the geometry BETWEEN the light and the view frustum that a
        // plain shadow-frustum test would drop — which is exactly what makes an off-screen object still cast a
        // shadow into the visible scene
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

    // True when the shadow pass has any block entity to draw at all — either all of them, or just the
    // light-emitting subset. Lets the caller skip the whole block-entity walk when neither applies
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
