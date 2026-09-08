package com.bdmajora.impetus.umbra.gl.sampler;

import com.bdmajora.impetus.umbra.gl.GlTextureUnits;

import java.util.function.IntSupplier;

/**
 * One sampler's texture unit and the texture it should hold (Umbra {@code gl/sampler/SamplerBinding}).
 * <p>
 * The texture is an {@link IntSupplier} rather than an id because the texture behind a render-target name changes when
 * the buffer flips; resolving it at bind time is what keeps a ping-ponged target correct.
 * <p>
 * Umbra binds through {@code UmbraRenderSystem.bindTextureToUnit}, which on a DSA driver is {@code glBindTextureUnit}
 * (the selector is never touched) and otherwise saves the active unit, binds, and restores — patching
 * {@code GlStateManager}'s cache by hand in both paths. This port has no DSA guarantee and cannot reach 1.12.2's
 * package-private cache from outside, so it routes through {@link GlTextureUnits} instead, which reaches the same end
 * state by only ever writing the cache through {@code GlStateManager} itself. See that class for why a raw bind here
 * would eventually produce a white screen.
 */
public final class SamplerBinding {
    private final int textureUnit;
    private final int textureTarget;
    private final IntSupplier texture;

    public SamplerBinding(int textureUnit, int textureTarget, IntSupplier texture) {
        this.textureUnit = textureUnit;
        this.textureTarget = textureTarget;
        this.texture = texture;
    }

    /** Binds this sampler's texture. Part of a run — the caller restores the selector once at the end. */
    public void update() {
        GlTextureUnits.bindTextureInRun(this.textureUnit, this.textureTarget, this.texture.getAsInt());
    }

    public int getTextureUnit() {
        return this.textureUnit;
    }
}
