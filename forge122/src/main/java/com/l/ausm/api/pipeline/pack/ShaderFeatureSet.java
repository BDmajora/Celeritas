package com.l.ausm.api.pipeline.pack;
import com.github.bsideup.jabel.Desugar;

import com.l.ausm.api.pipeline.fbo.*;
import com.l.ausm.api.pipeline.shader.*;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

@Desugar
public record ShaderFeatureSet(
        List<String> required,
        List<String> optional
) {
    public static ShaderFeatureSet empty() {
        return new ShaderFeatureSet(java.util.Collections.emptyList(), java.util.Collections.emptyList());
    }

    public static ShaderFeatureSet parse(Properties properties) {
        return new ShaderFeatureSet(
                parseList(properties.getProperty("iris.features.required")),
                parseList(properties.getProperty("iris.features.optional"))
        );
    }

    public boolean requires(String feature) {
        return required.contains(normalize(feature));
    }

    public boolean optional(String feature) {
        return optional.contains(normalize(feature));
    }

    private static List<String> parseList(String value) {
        if (value == null || value.trim().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return Arrays.stream(value.trim().split("\\s+"))
                .map(ShaderFeatureSet::normalize)
                .filter(token -> !token.trim().isEmpty())
                .distinct()
                .collect(java.util.stream.Collectors.toList());
    }

    private static String normalize(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if ("tesselation_shaders".equals(normalized)) {
            return "tessellation_shaders";
        }
        return normalized;
    }
}
