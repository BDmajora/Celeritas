package com.bdmajora.coarctatio.dedup;

import com.bdmajora.coarctatio.CoarctatioConfig;

// Long-lived string pools in the spirit of LoliASM's LoliStringPool, split per workload so chatty NBT cannot starve resource paths; String.intern cannot be sized or dropped
public final class StringPool {
    // NBT keys are extremely repetitive (id, Count, Damage, tag, x/y/z); sharded because packet decode, chunk IO and client threads intern concurrently
    public static final ShardedStringCache NBT_KEYS =
            new ShardedStringCache("NBT keys", CoarctatioConfig.get().poolSizeLimit);

    // Static-only
    private StringPool() {
    }
}
