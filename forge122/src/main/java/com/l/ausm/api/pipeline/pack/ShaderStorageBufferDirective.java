package com.l.ausm.api.pipeline.pack;
import com.github.bsideup.jabel.Desugar;

import com.l.ausm.api.pipeline.fbo.*;
import com.l.ausm.api.pipeline.shader.*;

@Desugar
public record ShaderStorageBufferDirective(
        int index,
        long size,
        boolean relative,
        float scaleX,
        float scaleY,
        String name
) {
}
