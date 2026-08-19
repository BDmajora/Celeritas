package com.bdmajora.coartatio.nbt;

import com.bdmajora.coartatio.CoartatioConfig;
import com.bdmajora.coartatio.dedup.StringPool;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.nbt.NBTBase;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Backing map for {@code NBTTagCompound}, replacing the {@code HashMap} vanilla allocates for every
 * single compound in the game.
 *
 * <p>Two things are going on, and they compound:
 *
 * <ol>
 *   <li><b>Array storage for small compounds.</b> A {@code HashMap} pays for a 16-slot table plus a
 *       32-byte {@code Node} per entry. The overwhelming majority of NBT compounds hold fewer than a
 *       dozen entries — an item stack is {@code id}/{@code Count}/{@code Damage}/{@code tag}, a tile
 *       entity is {@code id}/{@code x}/{@code y}/{@code z} plus a handful. For those, a flat
 *       key/value array is several times smaller and, at that size, faster to search. Past
 *       {@code nbtArrayMapThreshold} entries the map promotes itself to a hash map, because a linear
 *       scan does eventually lose.
 *   <li><b>Key interning.</b> Every compound with an {@code id} key holds its own {@code "id"}
 *       string unless it came off the same read path. Routing keys through a pool collapses them to
 *       one instance each.
 * </ol>
 *
 * <p>Derived from LoliASM's {@code LoliTagMap} and FoamFix's {@code FoamNBTTagCompoundMap}. Delegating
 * rather than extending a fastutil map is what makes the promotion possible, and it keeps
 * {@code equals}/{@code hashCode} answering for the delegate so
 * {@code NBTTagCompound.equals} — which compares the two backing maps directly — keeps working
 * against vanilla {@code HashMap}s from any mod that constructs one.
 */
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
