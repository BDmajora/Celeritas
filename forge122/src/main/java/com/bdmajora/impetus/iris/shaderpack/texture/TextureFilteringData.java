package com.bdmajora.impetus.iris.shaderpack.texture;

/**
 * The {@code blur}/{@code clamp} flags read from a custom texture's {@code .png.mcmeta} sidecar
 * ({@code {"texture": {"blur": true, "clamp": false}}}). Port of Iris's {@code TextureFilteringData}.
 * Defaults are {@code false}/{@code false}: nearest filtering, repeat wrapping.
 */
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
