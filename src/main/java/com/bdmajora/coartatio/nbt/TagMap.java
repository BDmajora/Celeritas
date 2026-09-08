package com.bdmajora.coartatio.nbt;

import com.bdmajora.coartatio.CoartatioConfig;
import com.bdmajora.coartatio.dedup.StringPool;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.nbt.NBTBase;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

// Backing map for NBTTagCompound; replaces vanilla's per-compound HashMap.
// Small compounds use a flat array (cheaper than a hash table below nbtArrayMapThreshold entries), promoting to a hash map once they grow past it.
// Keys optionally get interned through StringPool to collapse duplicate "id"-style strings. Derived from LoliASM's LoliTagMap / FoamFix's FoamNBTTagCompoundMap.
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

    @Override
    public void putAll(Map<? extends String, ? extends NBTBase> m) {
        // Routed through put() one at a time so interning and promotion both apply.
        for (Map.Entry<? extends String, ? extends NBTBase> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void clear() {
        // NBTTagCompound.read() clears before filling. Reverting to array storage means a compound
        // that was briefly large does not keep a large hash table alive.
        this.delegate = this.promotionThreshold > 0
                ? new Object2ObjectArrayMap<>()
                : new Object2ObjectOpenHashMap<>();
    }

    @Override
    public int size() {
        return this.delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return this.delegate.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return this.delegate.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return this.delegate.containsValue(value);
    }

    @Override
    public NBTBase get(Object key) {
        return this.delegate.get(key);
    }

    @Override
    public NBTBase remove(Object key) {
        return this.delegate.remove(key);
    }

    @Override
    public Set<String> keySet() {
        return this.delegate.keySet();
    }

    @Override
    public Collection<NBTBase> values() {
        return this.delegate.values();
    }

    @Override
    public Set<Entry<String, NBTBase>> entrySet() {
        return this.delegate.entrySet();
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this || this.delegate.equals(obj);
    }

    @Override
    public int hashCode() {
        return this.delegate.hashCode();
    }

    @Override
    public String toString() {
        return this.delegate.toString();
    }
}
