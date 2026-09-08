package com.bdmajora.impetus.umbra.shaderpack.texture;

/**
 * One {@code texture.<stage>.<sampler>} raw-texture directive resolved the Umbra way: instead of hijacking the sampler
 * unit of {@code <sampler>} for the whole stage, the pack's raw texture gets a freshly minted sampler name
 * ({@code customtex0}, {@code customtex1}, ...) and programs of {@code stage} that declare {@code <sampler>} with a
 * <em>matching sampler type</em> have that identifier renamed to it.
 * <p>
 * The type check is what makes Photon work: it declares {@code texture.deferred.colortex6 = ... TEXTURE_3D ...}, but
 * {@code colortex6} is a {@code sampler3D} worley-noise lookup in {@code deferred}/{@code deferred1} and a plain
 * {@code sampler2D} ambient-lighting buffer in {@code deferred3}/{@code deferred4}. Overriding the unit stage-wide
 * feeds the 3D texture to the 2D samplers, which then read black.
 *
 * @see com.bdmajora.impetus.umbra.shaderpack.texture.CustomTextureTransformer
 */
public final class CustomTexturePatch {
    private final String samplerName;
    private final TextureStage stage;
    /** The declared raw target, e.g. {@code TEXTURE_3D}. */
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
