package com.l.ausm.api.pipeline.pack;
import com.github.bsideup.jabel.Desugar;

@Desugar
public record ShaderImageDirective(
        String name,
        String samplerName,
        ShaderImageTarget target,
        String format,
        String internalFormat,
        String pixelType,
        boolean clear,
        boolean relative,
        int width,
        int height,
        int depth,
        float relativeWidth,
        float relativeHeight
) {
}
