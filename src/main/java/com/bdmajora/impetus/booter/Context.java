package com.bdmajora.impetus.booter;

import com.bdmajora.impetus.booter.service.ModDiscoverer;
import com.bdmajora.impetus.booter.util.Environment;

import java.util.Collection;

// This class contains loading context for callers
@Deprecated
public final class Context {

    public enum ModLoader {

        FORGE,
        CLEANROOM;

    }

    private final String mixinConfig;
    private final Collection<String> presentMods;

    public Context(String mixinConfig, Collection<String> presentMods) {
        this.mixinConfig = mixinConfig;
        this.presentMods = presentMods;
    }

    // Always FORGE on 1.12.2
    public ModLoader modLoader() {
        return ModLoader.FORGE;
    }

    // Whether launched through GradleStart
    public boolean inDev() {
        return Environment.inDev();
    }

    // The config being queried, or null for a hijacker
    public String mixinConfig() {
        return mixinConfig;
    }

    // From the discoverer's mod index
    public boolean isModPresent(String modId) {
        return presentMods.contains(modId);
    }

}
