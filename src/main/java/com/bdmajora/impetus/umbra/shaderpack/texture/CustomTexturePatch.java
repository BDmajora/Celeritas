package com.bdmajora.impetus.umbra.shaderpack.texture;

// One texture.<stage>.<sampler> directive, resolved the Iris way: the texture gets a minted name and only
// programs in that stage declaring <sampler> with a matching type are renamed to it
// The type check is what makes Photon render, whose colortex6 is sampler3D in some passes and sampler2D in others
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

    // The sampler the pack declared
    public String getSamplerName() {
        return this.samplerName;
    }

    // Which stage it applies to
    public TextureStage getStage() {
        return this.stage;
    }

    // sampler type expected
    public String getTextureType() {
        return this.textureType;
    }

    // The renamed sampler the pipeline actually binds
    public String getNewSamplerName() {
        return this.newSamplerName;
    }

    // For logging
    @Override
    public String toString() {
        return this.samplerName + " (" + this.textureType + ", " + this.stage + ") -> " + this.newSamplerName;
    }
}
