package com.bdmajora.impetus.umbra.pipeline;

import com.bdmajora.impetus.umbra.shaderpack.ShaderProperties;

// What the shadow pass may draw, from shadowTerrain, shadowEntities and similar directives; ignoring them costs frame time and shows shadows the pack omitted, defaults follow OptiFine
public final class ShadowContentSettings {
    // shadow.culling — how the shadow pass decides which chunks to walk
    public enum Culling {
        // Cull against the shadow frustum only
        ON,
        // No culling at all: every chunk in range is drawn
        OFF,
        // Iris's AdvancedShadowCullingFrustum: keeps geometry BETWEEN the light and the view frustum that a plain shadow-frustum test drops, so an off-screen object still casts into the visible scene
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

    // Everything rendered, no culling override
    public static ShadowContentSettings defaults() {
        return new ShadowContentSettings(true, true, true, true, false, true, Culling.ON);
    }

    // Reads the shadow.* content directives
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

    // true, false or reversed
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

    // shadowTerrain
    public boolean shouldRenderTerrain() {
        return this.terrain;
    }

    // shadowTranslucent
    public boolean shouldRenderTranslucent() {
        return this.translucent;
    }

    // shadowEntities
    public boolean shouldRenderEntities() {
        return this.entities;
    }

    // True when the shadow pass draws any block entity at all (all, or the light-emitting subset), so the caller can skip the whole walk otherwise
    public boolean shouldRenderAnyBlockEntities() {
        return this.blockEntities || this.lightBlockEntities;
    }

    // shadowBlockEntities
    public boolean shouldRenderBlockEntities() {
        return this.blockEntities;
    }

    // shadowLightBlockEntities, which restricts to emissive ones
    public boolean shouldRenderLightBlockEntitiesOnly() {
        return !this.blockEntities && this.lightBlockEntities;
    }

    // shadowPlayer
    public boolean shouldRenderPlayer() {
        return this.player;
    }

    // Frustum culling mode for the shadow pass
    public Culling getCulling() {
        return this.culling;
    }

    // For the startup log
    @Override
    public String toString() {
        return "terrain=" + this.terrain + ", translucent=" + this.translucent + ", entities=" + this.entities
                + ", blockEntities=" + this.blockEntities + ", lightBlockEntities=" + this.lightBlockEntities
                + ", player=" + this.player + ", culling=" + this.culling;
    }
}
