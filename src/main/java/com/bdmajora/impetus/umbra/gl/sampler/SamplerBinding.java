package com.bdmajora.impetus.umbra.gl.sampler;

import com.bdmajora.impetus.umbra.gl.GlTextureUnits;

import java.util.function.IntSupplier;

// One sampler's unit and the texture it should hold, resolved at bind time since a render target's texture
// changes when its buffer flips. Routes through GlTextureUnits; see that class for why a raw bind is unsafe here
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

    // The unit this sampler was allocated
    public int getTextureUnit() {
        return this.textureUnit;
    }
}
