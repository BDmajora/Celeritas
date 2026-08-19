package com.bdmajora.coartatio.dedup;

import com.bdmajora.coartatio.CoartatioConfig;

/**
 * Long-lived string pools, in the spirit of LoliASM's {@code LoliStringPool}.
 *
 * <p>Kept as separate pools rather than one global one on purpose. NBT keys and resource paths have
 * completely different lifetimes and cardinalities, and a shared pool would mean a chatty modded NBT
 * workload saturating the cap and starving the resource paths — which are the entries worth keeping.
 *
 * <p>{@link String#intern()} would do the same job, but it puts entries in the JVM's string table,
 * which we can neither size nor drop, and which is shared with every other consumer in the process.
 */
public final class StringPool {
    /**
     * Keys of {@code NBTTagCompound}. Extremely repetitive — {@code id}, {@code Count},
     * {@code Damage}, {@code tag}, {@code x}/{@code y}/{@code z} account for most of a typical world.
     *
     * <p>Sharded rather than singly locked: these are interned from the packet decode thread, the
     * chunk IO thread and the client thread simultaneously while a world streams in.
     */
    public static final ShardedStringCache NBT_KEYS =
            new ShardedStringCache("NBT keys", CoartatioConfig.get().poolSizeLimit);

    private StringPool() {
    }
}
