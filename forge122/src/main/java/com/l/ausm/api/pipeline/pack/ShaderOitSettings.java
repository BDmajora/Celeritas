package com.l.ausm.api.pipeline.pack;
import com.github.bsideup.jabel.Desugar;

import com.l.ausm.api.pipeline.fbo.Attachment;
import com.l.ausm.api.pipeline.fbo.ColorBufferFormat;

import java.util.List;
import java.util.Map;

@Desugar
public record ShaderOitSettings(
        boolean enabled,
        List<Integer> gbufferCoefficientRanks,
        Map<Attachment, BufferMode> gbufferBuffers,
        Map<Attachment, ColorBufferFormat> gbufferFormats
) {
    public ShaderOitSettings {
        gbufferCoefficientRanks = gbufferCoefficientRanks == null ? java.util.Collections.emptyList() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(gbufferCoefficientRanks));
        gbufferBuffers = gbufferBuffers == null ? java.util.Collections.emptyMap() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(gbufferBuffers));
        gbufferFormats = gbufferFormats == null ? java.util.Collections.emptyMap() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(gbufferFormats));
    }

    public static ShaderOitSettings empty() {
        return new ShaderOitSettings(false, java.util.Collections.emptyList(), java.util.Collections.emptyMap(), java.util.Collections.emptyMap());
    }

    public boolean activeForGbuffers() {
        return enabled && !gbufferBuffers.isEmpty();
    }

    public boolean coefficientBuffer(Attachment attachment) {
        BufferMode mode = gbufferBuffers.get(attachment);
        return mode != null && mode.type() == BufferMode.Type.COEFFICIENT;
    }

    public boolean frontmostBuffer(Attachment attachment) {
        BufferMode mode = gbufferBuffers.get(attachment);
        return mode != null && mode.type() == BufferMode.Type.FRONTMOST;
    }

    @Desugar
    public record BufferMode(Type type, int coefficientIndex) {
        public enum Type {
            COEFFICIENT,
            FRONTMOST
        }

        public static BufferMode coefficient(int index) {
            return new BufferMode(Type.COEFFICIENT, Math.max(0, index));
        }

        public static BufferMode frontmost() {
            return new BufferMode(Type.FRONTMOST, -1);
        }
    }
}
