package com.bdmajora.impetus.core;

import com.gtnewhorizons.retrofuturabootstrap.SharedConfig;

public class ImpetusLwjgl3ifyCompat {
    
    // Excludes Impetus packages from lwjgl3ify's redirect transformer, which would otherwise double-rewrite our LWJGL calls
    public static void apply() {
        // Exclude Impetus packages from lwjgl3ify's redirect transformer to prevent bytecode conflicts (TODO: move to retrofuturabootstrap's config or manifest to avoid state mutation races)
        SharedConfig.getRfbTransformers().stream()
                .filter(transformer -> "lwjgl3ify:redirect".equals(transformer.id()))
                .findFirst()
                .ifPresent(handle -> {
                    handle.exclusions().add("com.bdmajora.impetus.engine");
                    handle.exclusions().add("com.bdmajora.impetus");
                });
    }
}