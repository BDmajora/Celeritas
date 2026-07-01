package com.l.ausm.api.pipeline.shader;
import com.github.bsideup.jabel.Desugar;

@Desugar
public record ShaderIndirectPointer(int buffer, long offset) {
}
