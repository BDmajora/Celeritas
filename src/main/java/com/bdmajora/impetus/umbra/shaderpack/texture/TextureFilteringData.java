package com.bdmajora.impetus.umbra.shaderpack.texture;

// The blur/clamp flags from a custom texture's .png.mcmeta ({"texture":{"blur":..,"clamp":..}}): blur picks GL_LINEAR, clamp picks GL_CLAMP_TO_EDGE, both default false like vanilla
public final class TextureFilteringData {
    private final boolean blur;
    private final boolean clamp;

    public TextureFilteringData(boolean blur, boolean clamp) {
        this.blur = blur;
        this.clamp = clamp;
    }

    // LINEAR rather than NEAREST
    public boolean shouldBlur() {
        return this.blur;
    }

    // CLAMP_TO_EDGE rather than REPEAT
    public boolean shouldClamp() {
        return this.clamp;
    }

    // For logging
    @Override
    public String toString() {
        return "TextureFilteringData{blur=" + this.blur + ", clamp=" + this.clamp + "}";
    }
}
