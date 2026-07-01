package com.l.ausm.api.pipeline.pack;
import com.github.bsideup.jabel.Desugar;

import com.l.ausm.api.pipeline.fbo.*;
import com.l.ausm.api.pipeline.shader.*;
import com.l.ausm.api.pipeline.pack.*;

import com.l.ausm.api.pipeline.shader.ComputeProgramSource;
import com.l.ausm.api.pipeline.shader.ProgramArrayId;

import java.util.List;
import java.util.Map;

@Desugar
public record ShaderComputeDirectives(
        Map<ProgramArrayId, List<ComputeProgramSource>> computeArrays,
        List<ComputeProgramSource> shadowComputes,
        List<ComputeProgramSource> finalComputes
) {
    public static ShaderComputeDirectives empty() {
        return new ShaderComputeDirectives(java.util.Collections.emptyMap(), java.util.Collections.emptyList(), java.util.Collections.emptyList());
    }

    public boolean hasComputes() {
        if (!shadowComputes.isEmpty() || !finalComputes.isEmpty()) {
            return true;
        }
        return computeArrays.values().stream().anyMatch(list -> !list.isEmpty());
    }
}
