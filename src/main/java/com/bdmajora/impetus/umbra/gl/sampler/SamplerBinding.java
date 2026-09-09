package com.bdmajora.impetus.umbra.gl.sampler;

import com.bdmajora.impetus.umbra.gl.GlTextureUnits;

import java.util.function.IntSupplier;

// One sampler's texture unit and the texture that unit should hold
// The texture is an IntSupplier rather than a stored id because the texture behind a render-target name CHANGES
// when its buffer flips — resolving at bind time is what keeps a ping-ponged target reading the right half
// Iris binds through IrisRenderSystem.bindTextureToUnit, which on a DSA driver is glBindTextureUnit (the selector
// is never touched at all) and otherwise saves the active unit, binds, and restores — patching GlStateManager's
// cache by hand in both paths
// This port can do neither: there is no DSA guarantee here, and 1.12.2's cache is package-private and unreachable
// from outside. So it routes through GlTextureUnits, which reaches the same end state by only ever writing that
// cache through GlStateManager itself. See that class for why a raw bind here eventually produces a white screen
public final class SamplerBinding {
    private final int textureUnit;
    private final int textureTarget;
    private final IntSupplier texture;

    public SamplerBinding(int textureUnit, int textureTarget, IntSupplier texture) {
        this.textureUnit = textureUnit;
        this.textureTarget = textureTarget;
        this.texture = texture;
    }

    // Binds this sampler's texture. Deliberately does NOT restore the texture-unit selector: this is one step of a
    // run of bindings, and the caller restores the selector once when the whole run is done
    public void update() {
        GlTextureUnits.bindTextureInRun(this.textureUnit, this.textureTarget, this.texture.getAsInt());
    }

    public int getTextureUnit() {
        return this.textureUnit;
    }
}
