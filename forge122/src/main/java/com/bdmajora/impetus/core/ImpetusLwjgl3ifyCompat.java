package com.bdmajora.impetus.core;

import com.gtnewhorizons.retrofuturabootstrap.SharedConfig;

public class ImpetusLwjgl3ifyCompat {
    public static void apply() {
        // Hack for now
        var handle = SharedConfig.getRfbTransformers().stream().filter(transformer -> transformer.id().equals("lwjgl3ify:redirect")).findFirst().get();
        handle.exclusions().add("com.bdmajora.impetus.engine");
        handle.exclusions().add("com.bdmajora.impetus");
    }
}
