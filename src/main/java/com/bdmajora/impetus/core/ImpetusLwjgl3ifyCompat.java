package com.bdmajora.impetus.core;

import com.gtnewhorizons.retrofuturabootstrap.SharedConfig;

public class ImpetusLwjgl3ifyCompat {
    
    public static void apply() {
        // TODO: Move these exclusions to retrofuturabootstrap's config file or manifest to avoid race conditions and state mutation.
        // Exclude Impetus packages from lwjgl3ify's redirect transformer to prevent bytecode conflicts.
        SharedConfig.getRfbTransformers().stream()
                .filter(transformer -> "lwjgl3ify:redirect".equals(transformer.id()))
                .findFirst()
                .ifPresent(handle -> {
                    handle.exclusions().add("com.bdmajora.impetus.engine");
                    handle.exclusions().add("com.bdmajora.impetus");
                });
    }
}