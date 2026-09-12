package com.bdmajora.coartatio.dedup;

import com.bdmajora.coartatio.CoartatioConfig;

// Pools for the two strings every ResourceLocation carries; a modded instance holds hundreds of thousands
// Session-lifetime because ResourceLocations never stop being constructed; the cache size cap bounds them
public final class ResourceLocationCaches {
    // Nearly always one of a few hundred mod ids, so this column collapses almost completely
    public static final DeduplicationCache<String> DOMAINS =
            new DeduplicationCache<>("Resource domains", CoartatioConfig.get().poolSizeLimit);

    // Repeat less than domains, but still share heavily across the variants of a single block
    public static final DeduplicationCache<String> PATHS =
            new DeduplicationCache<>("Resource paths", CoartatioConfig.get().poolSizeLimit);

    // Static-only
    private ResourceLocationCaches() {
    }
}
