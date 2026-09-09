package com.bdmajora.impetus.umbra.shaderpack.texture;

// One `texture.<stage>.<sampler>` raw-texture directive, resolved the Iris way
// Rather than hijacking the sampler unit of <sampler> for the whole stage, the pack's raw texture is given a
// freshly minted sampler name (customtex0, customtex1, ...) and only those programs in <stage> that declare
// <sampler> with a MATCHING sampler type get that identifier renamed to the new one
// The type check is what makes Photon render. It declares texture.deferred.colortex6 = ... TEXTURE_3D ..., but
// colortex6 is a sampler3D worley-noise lookup in deferred/deferred1 and a plain sampler2D ambient-lighting buffer
// in deferred3/deferred4. Overriding the unit across the whole stage feeds the 3D texture to the 2D samplers,
// which then read black
// See CustomTextureTransformer for the rename itself
public final class CustomTexturePatch {
    private final String samplerName;
    private final TextureStage stage;
    // The declared raw target, e.g. TEXTURE_3D — this is the half the transformer type-checks against
    private final String textureType;
    private final String newSamplerName;

    public CustomTexturePatch(String samplerName, TextureStage stage, String textureType, String newSamplerName) {
        this.samplerName = samplerName;
        this.stage = stage;
        this.textureType = textureType;
        this.newSamplerName = newSamplerName;
    }

    public String getSamplerName() {
        return this.samplerName;
    }

    public TextureStage getStage() {
        return this.stage;
    }

    public String getTextureType() {
        return this.textureType;
    }

    public String getNewSamplerName() {
        return this.newSamplerName;
    }

    @Override
    public String toString() {
        return this.samplerName + " (" + this.textureType + ", " + this.stage + ") -> " + this.newSamplerName;
    }
}
