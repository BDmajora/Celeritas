package com.bdmajora.coartatio.dedup;

import com.bdmajora.coartatio.CoartatioConfig;

/**
 * Pools for the two strings every {@code ResourceLocation} carries.
 *
 * <p>A modded instance holds hundreds of thousands of {@code ResourceLocation}s — one per item
 * model, per texture, per recipe, per registry entry, per sound. The domain is nearly always one of
 * a few hundred mod ids, so pooling domains collapses that column almost completely. Paths are less
 * repetitive but still share heavily across variants of the same block.
 *
 * <p>Both pools live for the whole session; unlike the model pools there is no point at which no
 * further {@code ResourceLocation}s will be constructed. The cap in
 * {@link DeduplicationCache} is what keeps that safe.
 */
public final class ResourceLocationCaches {
    public static final DeduplicationCache<String> DOMAINS =
            new DeduplicationCache<>("Resource domains", CoartatioConfig.get().poolSizeLimit);

    public static final DeduplicationCache<String> PATHS =
            new DeduplicationCache<>("Resource paths", CoartatioConfig.get().poolSizeLimit);

    private ResourceLocationCaches() {
    }
}
