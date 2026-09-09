package com.bdmajora.impetus.umbra.shaderpack.texture;

// The blur/clamp flags read from a custom texture's .png.mcmeta sidecar, i.e. {"texture":{"blur":..,"clamp":..}}
// blur picks GL_LINEAR over GL_NEAREST; clamp picks GL_CLAMP_TO_EDGE over GL_REPEAT
// Both default to false when the pack ships no sidecar, matching vanilla: nearest filtering, repeat wrapping
public final class TextureFilteringData {
    private final boolean blur;
    private final boolean clamp;

    public TextureFilteringData(boolean blur, boolean clamp) {
        this.blur = blur;
        this.clamp = clamp;
    }

    public boolean shouldBlur() {
        return this.blur;
    }

    public boolean shouldClamp() {
        return this.clamp;
    }

    @Override
    public String toString() {
        return "TextureFilteringData{blur=" + this.blur + ", clamp=" + this.clamp + "}";
    }
}
