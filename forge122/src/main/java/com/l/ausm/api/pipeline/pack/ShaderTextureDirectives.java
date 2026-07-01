package com.l.ausm.api.pipeline.pack;
import com.github.bsideup.jabel.Desugar;

import com.l.ausm.api.pipeline.fbo.*;
import com.l.ausm.api.pipeline.shader.*;

import com.l.ausm.api.pipeline.shader.ProgramId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Iris-style custom texture binding directives keyed by shader program identity.
 */
@Desugar
public record ShaderTextureDirectives(
        List<ShaderCustomTextureBinding> globalTextures,
        Map<ProgramId, List<ShaderCustomTextureBinding>> programTextures,
        Map<ShaderProgramArrayKey, List<ShaderCustomTextureBinding>> programArrayTextures,
        List<ShaderRawTextureDirective> rawTextures,
        Map<ProgramId, List<ShaderRawTextureDirective>> programRawTextures,
        Map<ShaderProgramArrayKey, List<ShaderRawTextureDirective>> programArrayRawTextures
) {
    public static ShaderTextureDirectives empty() {
        return new ShaderTextureDirectives(java.util.Collections.emptyList(), java.util.Collections.emptyMap(), java.util.Collections.emptyMap(), java.util.Collections.emptyList(), java.util.Collections.emptyMap(), java.util.Collections.emptyMap());
    }

    public List<ShaderCustomTextureBinding> texturesFor(ProgramId programId) {
        List<ShaderCustomTextureBinding> local = programTextures.get(programId);
        if (local == null || local.isEmpty()) {
            return globalTextures;
        }

        List<ShaderCustomTextureBinding> merged = new ArrayList<>(globalTextures.size() + local.size());
        merged.addAll(globalTextures);
        merged.addAll(local);
        return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(merged));
    }

    public List<ShaderRawTextureDirective> rawTexturesFor(ProgramId programId) {
        List<ShaderRawTextureDirective> local = programRawTextures.get(programId);
        if (local == null || local.isEmpty()) {
            return rawTextures;
        }

        List<ShaderRawTextureDirective> merged = new ArrayList<>(rawTextures.size() + local.size());
        merged.addAll(rawTextures);
        merged.addAll(local);
        return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(merged));
    }

    public List<ShaderCustomTextureBinding> texturesFor(ProgramArrayId arrayId, int index) {
        List<ShaderCustomTextureBinding> local = programArrayTextures.get(new ShaderProgramArrayKey(arrayId, index));
        if (local == null || local.isEmpty()) {
            return globalTextures;
        }

        List<ShaderCustomTextureBinding> merged = new ArrayList<>(globalTextures.size() + local.size());
        merged.addAll(globalTextures);
        merged.addAll(local);
        return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(merged));
    }

    public List<ShaderRawTextureDirective> rawTexturesFor(ProgramArrayId arrayId, int index) {
        List<ShaderRawTextureDirective> local = programArrayRawTextures.get(new ShaderProgramArrayKey(arrayId, index));
        if (local == null || local.isEmpty()) {
            return rawTextures;
        }

        List<ShaderRawTextureDirective> merged = new ArrayList<>(rawTextures.size() + local.size());
        merged.addAll(rawTextures);
        merged.addAll(local);
        return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(merged));
    }

    public int rawTextureCount() {
        return rawTextures.size()
                + programRawTextures.values().stream().mapToInt(List::size).sum()
                + programArrayRawTextures.values().stream().mapToInt(List::size).sum();
    }
}
