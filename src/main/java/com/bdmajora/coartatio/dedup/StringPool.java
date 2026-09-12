package com.bdmajora.coartatio.dedup;

import com.bdmajora.coartatio.CoartatioConfig;

// Long-lived string pools, in the spirit of LoliASM's LoliStringPool
// Split per workload so chatty NBT cannot saturate the cap and starve resource paths; String.intern would
// put entries in the JVM string table, which cannot be sized or dropped
public final class StringPool {
    // NBT keys are extremely repetitive: id, Count, Damage, tag and x/y/z dominate a typical world
    // Sharded because packet decode, chunk IO and client threads intern concurrently while a world streams
    public static final ShardedStringCache NBT_KEYS =
            new ShardedStringCache("NBT keys", CoartatioConfig.get().poolSizeLimit);

    // Static-only
    private StringPool() {
    }
}
