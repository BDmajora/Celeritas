package com.bdmajora.coarctatio.dedup;

import com.bdmajora.coarctatio.CoarctatioConfig;

// Pools for the two strings every ResourceLocation carries (hundreds of thousands on a modded instance); session-lifetime, bounded by the cache cap
public final class ResourceLocationCaches {
    // Nearly always one of a few hundred mod ids, so this column collapses almost completely
    public static final DeduplicationCache<String> DOMAINS =
            new DeduplicationCache<>("Resource domains", CoarctatioConfig.get().poolSizeLimit);

    // Repeat less than domains, but still share heavily across the variants of a single block
    public static final DeduplicationCache<String> PATHS =
            new DeduplicationCache<>("Resource paths", CoarctatioConfig.get().poolSizeLimit);

    // Static-only
    private ResourceLocationCaches() {
    }
}
