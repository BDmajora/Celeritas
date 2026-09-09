package com.bdmajora.coartatio.dedup;

import com.bdmajora.coartatio.CoartatioConfig;

// Long-lived string pools, in the spirit of LoliASM's LoliStringPool
// Kept as separate pools rather than one global one on purpose: NBT keys and resource paths have entirely
// different lifetimes and cardinalities, and sharing a pool would let a chatty modded NBT workload saturate the
// cap and starve the resource paths, which are the entries actually worth keeping
// String.intern() would do the same job, but it puts entries in the JVM's own string table, which cannot be
// sized or dropped and is shared with every other consumer in the process
public final class StringPool {
    // NBTTagCompound keys, which are extremely repetitive: id, Count, Damage, tag and x/y/z account for most of
    // a typical world's compounds
    // Sharded rather than singly locked because these are interned from the packet decode thread, the chunk IO
    // thread and the client thread at the same time while a world streams in
    public static final ShardedStringCache NBT_KEYS =
            new ShardedStringCache("NBT keys", CoartatioConfig.get().poolSizeLimit);

    private StringPool() {
    }
}
