package com.bdmajora.coartatio.dedup;

import com.bdmajora.coartatio.CoartatioConfig;

// Pools for the two strings every ResourceLocation carries
// A modded instance holds hundreds of thousands of them: one per item model, texture, recipe, registry entry
// and sound
// The domain is nearly always one of a few hundred mod ids, so pooling that column collapses it almost
// completely. Paths repeat less, but still share heavily across the variants of a single block
// Both pools live for the whole session, because unlike the model pools there is no point after which no more
// ResourceLocations get constructed. DeduplicationCache's size cap is what keeps that from being a leak
public final class ResourceLocationCaches {
    public static final DeduplicationCache<String> DOMAINS =
            new DeduplicationCache<>("Resource domains", CoartatioConfig.get().poolSizeLimit);

    public static final DeduplicationCache<String> PATHS =
            new DeduplicationCache<>("Resource paths", CoartatioConfig.get().poolSizeLimit);

    private ResourceLocationCaches() {
    }
}
