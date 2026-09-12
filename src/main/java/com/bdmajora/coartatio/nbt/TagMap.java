package com.bdmajora.coartatio.nbt;

import com.bdmajora.coartatio.CoartatioConfig;
import com.bdmajora.coartatio.dedup.StringPool;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.nbt.NBTBase;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

// Backing map for NBTTagCompound, replacing vanilla's per-compound HashMap
// Small compounds use a flat array and promote to a hash map past the configured threshold
// Keys are optionally interned through StringPool; derived from LoliASM's LoliTagMap and FoamFix
public class TagMap implements Map<String, NBTBase> {
    private final int promotionThreshold;
    private final boolean internKeys;

    private Map<String, NBTBase> delegate;

    public TagMap() {
        CoartatioConfig config = CoartatioConfig.get();

        this.promotionThreshold = config.nbtArrayMapThreshold;
        this.internKeys = config.internNbtKeys;
        this.delegate = this.promotionThreshold > 0
                ? new Object2ObjectArrayMap<>()
                : new Object2ObjectOpenHashMap<>();
    }

    // Interns the key if enabled, then promotes to a hash map when the array form would get too slow
    @Override
    public NBTBase put(String key, NBTBase value) {
        if (this.internKeys) {
            key = StringPool.NBT_KEYS.deduplicate(key);
        }

        if (this.promotionThreshold > 0
                && this.delegate.size() >= this.promotionThreshold
                && this.delegate instanceof Object2ObjectArrayMap
                && !this.delegate.containsKey(key)) {
            this.delegate = new Object2ObjectOpenHashMap<>(this.delegate);
        }

        return this.delegate.put(key, value);
    }

    // Routed through put one at a time so interning and promotion both apply
    @Override
    public void putAll(Map<? extends String, ? extends NBTBase> m) {
        // Routed through put() one at a time so interning and promotion both apply.
        for (Map.Entry<? extends String, ? extends NBTBase> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    // read() clears before filling, so this also demotes back to array storage to drop a large table
    @Override
    public void clear() {
        // NBTTagCompound.read() clears before filling. Reverting to array storage means a compound
        // that was briefly large does not keep a large hash table alive.
        this.delegate = this.promotionThreshold > 0
                ? new Object2ObjectArrayMap<>()
                : new Object2ObjectOpenHashMap<>();
    }

    // Delegates to whichever backing map is current
    @Override
    public int size() {
        return this.delegate.size();
    }

    // Delegates
    @Override
    public boolean isEmpty() {
        return this.delegate.isEmpty();
    }

    // Delegates
    @Override
    public boolean containsKey(Object key) {
        return this.delegate.containsKey(key);
    }

    // Delegates
    @Override
    public boolean containsValue(Object value) {
        return this.delegate.containsValue(value);
    }

    // Delegates; the hot path for every NBT read
    @Override
    public NBTBase get(Object key) {
        return this.delegate.get(key);
    }

    // Delegates; never demotes, since a shrinking compound is rare
    @Override
    public NBTBase remove(Object key) {
        return this.delegate.remove(key);
    }

    // Delegates
    @Override
    public Set<String> keySet() {
        return this.delegate.keySet();
    }

    // Delegates
    @Override
    public Collection<NBTBase> values() {
        return this.delegate.values();
    }

    // Delegates
    @Override
    public Set<Entry<String, NBTBase>> entrySet() {
        return this.delegate.entrySet();
    }

    // Map equality by contents, regardless of which backing form each side uses
    @Override
    public boolean equals(Object obj) {
        return obj == this || this.delegate.equals(obj);
    }

    // Delegates, consistent with equals
    @Override
    public int hashCode() {
        return this.delegate.hashCode();
    }

    // Delegates
    @Override
    public String toString() {
        return this.delegate.toString();
    }
}
