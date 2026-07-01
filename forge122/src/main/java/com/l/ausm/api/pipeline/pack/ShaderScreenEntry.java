package com.l.ausm.api.pipeline.pack;
import com.github.bsideup.jabel.Desugar;

import com.l.ausm.api.pipeline.fbo.*;
import com.l.ausm.api.pipeline.shader.*;

@Desugar
public record ShaderScreenEntry(Type type, String name) {
    public enum Type {
        OPTION,
        SCREEN,
        PROFILE,
        EMPTY
    }
}
