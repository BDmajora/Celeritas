package com.bdmajora.coartatio.state;

import com.bdmajora.coartatio.CoartatioConfig;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

// Packs every listed property of a block into the bits of one int, so a state needs a single field instead of
// a per-state lookup table
//
// What this replaces: vanilla gives every StateImplementation its own
// ImmutableTable<IProperty, Comparable, IBlockState> mapping "this property set to that value" onto the
// resulting state. For a block with P properties averaging V values that is P * (V - 1) table cells PER STATE,
// and there are V^P states. It is the single largest structure in a modded heap — routinely 250-400 MB across a
// 300-mod pack — and it is pure redundancy, since every state's table is derivable from the block's property set
//
// Here each property gets a contiguous bit range wide enough to index its allowed values. A state is then just
// an index into one IBlockState[] shared by every state of the block, and withProperty becomes a mask-and-index
// rather than a hash lookup in a per-state table. The table disappears; what is left is one array per block
// plus one int per state
//
// The technique is FoamFix's, and FerriteCore later did the same on modern versions. This port differs in three
// ways that matter
// Value indices resolve through the block's OWN property instance. FoamFix keys its per-property value tables
// by identity, so passing an equal-but-distinct IProperty — which vanilla accepts, because a state's property
// map is equals-keyed — silently builds a second mapping with a possibly different ordering. Here the property
// NAME resolves to this block's entry and the value is looked up inside that entry
// Allocation is bounded. Bit ranges are padded to powers of two, so the state array can be larger than the
// state count; FoamFix only rejects a block past 31 bits, which permits a multi-gigabyte array. Anything over
// MAX_STATE_ARRAY slots is refused here and that block keeps vanilla states
// The table can still be produced on demand — see CoartatioBlockState.getPropertyValueTable()
//
// Layout: properties are ordered by how much of their bit range they waste, least first. That puts the
// worst-fitting property in the HIGH bits, where its unused range is simply never addressed off the end of the
// array rather than multiplying through every lower property
public final class PropertyValueMapper {
    // Hard ceiling on the shared state array, a million slots. A block needing more than that is pathological,
    // and falling back to vanilla for it costs a table that was never going to fit anyway
    private static final int MAX_STATE_ARRAY = 1 << 20;

    // Entries are per-property and immutable, so every block sharing a static property (BlockHorizontal.FACING
    // and friends) shares one entry rather than rebuilding it
    // Reference-keyed on purpose: two equal-but-distinct properties may order their values differently, so they
    // must NOT collapse to one entry here
    private static final Map<IProperty<?>, Entry> ENTRY_CACHE = new Reference2ObjectOpenHashMap<>();

    // Counters for the memory report; atomic because block registration is not confined to one thread once mods
    // register from their own init
    private static final AtomicInteger BLOCKS_MAPPED = new AtomicInteger();
    private static final AtomicInteger BLOCKS_SKIPPED = new AtomicInteger();
    static final AtomicInteger TABLES_MATERIALISED = new AtomicInteger();

    // Orders properties least-wasteful first, waste being padded width minus real value count
    // The worst fit therefore lands in the high bits, where its unused tail runs off the end of the array
    // instead of multiplying through every property below it
    // Ties break on the property name so the layout is deterministic across launches, which matters because a
    // packed value is meaningless without the layout that produced it
    private static final Comparator<Entry> BY_BIT_FITNESS = (a, b) -> {
        int wasteA = a.bitSize - a.count;
        int wasteB = b.bitSize - b.count;

        return wasteA == wasteB
                ? a.property.getName().compareTo(b.property.getName())
                : Integer.compare(wasteA, wasteB);
    };

    // Sorted by BY_BIT_FITNESS, so index order here is bit order, not the container's property order
    private final Entry[] entries;
    // offsets[i] is the low bit of entries[i]'s slice within the packed int
    private final int[] offsets;
    // Property name -> index into entries; -1 for anything this block does not have
    private final Object2IntOpenHashMap<String> indexByName;
    // Every state of the block, indexed by its packed value. Shared by all of them — this is the array that
    // replaces the per-state tables
    private final IBlockState[] states;

    // Donated by the first state of this block and then reused by all of them; see sharedKeys below
    private Object[] sharedKeys;

    private PropertyValueMapper(Entry[] entries, int[] offsets, Object2IntOpenHashMap<String> indexByName,
                                IBlockState[] states) {
        this.entries = entries;
        this.offsets = offsets;
        this.indexByName = indexByName;
        this.states = states;
    }

    // Builds a mapper for a container, or returns null when this block should keep vanilla states
    // Every rejection path is a plain null return, so the caller never has to distinguish between blacklisted,
    // unindexable and too-large — all three simply mean "leave this block alone"
    // Safe to call from inside BlockStateContainer's constructor, because the property map is assigned before
    // the state loop that calls createState
    public static PropertyValueMapper create(BlockStateContainer container, Block block) {
        if (block == null || isBlacklisted(block)) {
            BLOCKS_SKIPPED.incrementAndGet();
            return null;
        }

        Collection<IProperty<?>> properties = container.getProperties();

        List<Entry> sorted = new ArrayList<>(properties.size());
        for (IProperty<?> property : properties) {
            Entry entry = entryFor(property);

            if (entry == null) {
                BLOCKS_SKIPPED.incrementAndGet();
                return null;
            }

            sorted.add(entry);
        }
        sorted.sort(BY_BIT_FITNESS);

        Entry[] entries = sorted.toArray(new Entry[0]);
        int[] offsets = new int[entries.length];
        Object2IntOpenHashMap<String> indexByName = new Object2IntOpenHashMap<>(entries.length);
        indexByName.defaultReturnValue(-1);

        int bitPos = 0;
        for (int i = 0; i < entries.length; i++) {
            offsets[i] = bitPos;
            indexByName.put(entries[i].property.getName(), i);
            bitPos += entries[i].bits;
        }

        // 30 rather than 31: the packed value is a signed int and the array index derived from it
        // must stay positive.
        if (bitPos > 30) {
            BLOCKS_SKIPPED.incrementAndGet();
            return null;
        }

        long size;
        if (entries.length == 0) {
            size = 1;
        } else {
            // The highest property does not need its range padded to a power of two — nothing is
            // packed above it, so the unused tail is simply never addressed.
            Entry last = entries[entries.length - 1];
            size = (1L << (bitPos - last.bits)) * last.count;
        }

        if (size > MAX_STATE_ARRAY) {
            BLOCKS_SKIPPED.incrementAndGet();
            return null;
        }

        BLOCKS_MAPPED.incrementAndGet();
        return new PropertyValueMapper(entries, offsets, indexByName, new IBlockState[(int) size]);
    }

    private static boolean isBlacklisted(Block block) {
        String name = block.getClass().getName();

        for (String prefix : CoartatioConfig.get().blockStateBlacklist) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    // Computes this state's packed index, files the state in the shared array, and hands the index back
    // Called from buildPropertyValueTable, which the container invokes on every state once all of them exist,
    // so by the time anything can call byValue the array is fully populated
    int register(IBlockState state) {
        int value = 0;

        for (int i = 0; i < this.entries.length; i++) {
            Entry entry = this.entries[i];
            int index = entry.indexOf(state.getValue(entry.property));

            if (index < 0) {
                // The state came out of the container's own cartesian product, so its value is by
                // construction an allowed one. Reaching here means an IProperty whose
                // getAllowedValues() disagrees with itself between calls.
                throw new IllegalStateException("Property " + entry.property.getName()
                        + " rejected its own allowed value on " + state);
            }

            value |= index << this.offsets[i];
        }

        if (this.states[value] != null) {
            // Two distinct states packing to the same index means the bit layout does not separate
            // them — a corrupted world waiting to happen. Fail here, where the cause is visible.
            throw new IllegalStateException("Packed index " + value + " is claimed by both "
                    + this.states[value] + " and " + state
                    + ". Please report this to Impetus with the mod list.");
        }

        this.states[value] = state;
        com.bdmajora.coartatio.MemoryReport.recordPackedStates(1);
        return value;
    }

    // The state at a packed index, or null when that bit combination lands in a padding hole no real state
    // occupies
    public IBlockState byValue(int value) {
        return this.states[value];
    }

    // The property key array every state of this block shares, for CoartatioPropertyMap
    // Captured from the FIRST state's own property map rather than derived from entries, because the compact map
    // indexes against that map's iteration order while entries has been reordered for bit-packing fitness — the
    // two orders are not the same and using the wrong one silently mismatches keys with values
    // Returns null when the caller's map does not have the same shape as the donated array, which leaves that
    // one state on Guava's map instead of risking a wrong pairing
    // Synchronized because the first-state capture is a lazy write and states can be created off-thread
    public synchronized Object[] sharedKeys(Map<IProperty<?>, Comparable<?>> properties) {
        if (this.sharedKeys == null) {
            this.sharedKeys = properties.keySet().toArray();
        }

        return this.sharedKeys.length == properties.size() ? this.sharedKeys : null;
    }

    // Returns packed with one property changed to newValue, or -1 when this block has no such property or the
    // value is not one it allows
    // This is what replaces vanilla's per-state table lookup in withProperty: clear the property's bit slice and
    // OR the new index into it
    // Resolved by property NAME, so an equal-but-distinct IProperty instance still finds this block's entry —
    // the difference from FoamFix noted at the top of the file
    public int withValue(int packed, IProperty<?> property, Object newValue) {
        int index = this.indexByName.getInt(property.getName());

        if (index < 0) {
            return -1;
        }

        Entry entry = this.entries[index];
        int valueIndex = entry.indexOf(newValue);

        if (valueIndex < 0) {
            return -1;
        }

        // bitSize is a power of two, so bitSize - 1 is the slice's low-bit mask, shifted into position
        int offset = this.offsets[index];
        int mask = (entry.bitSize - 1) << offset;

        return (packed & ~mask) | (valueIndex << offset);
    }

    public static String statistics() {
        return String.format("%d blocks packed, %d left on vanilla states, %d tables rebuilt on demand",
                BLOCKS_MAPPED.get(), BLOCKS_SKIPPED.get(), TABLES_MATERIALISED.get());
    }

    // Cache lookup for buildEntry. Note a null result is NOT cached, so an unindexable property is rebuilt (and
    // re-rejected) once per block that declares it — cheap, and it keeps the cache free of null values
    private static Entry entryFor(IProperty<?> property) {
        synchronized (ENTRY_CACHE) {
            Entry cached = ENTRY_CACHE.get(property);

            if (cached == null) {
                cached = buildEntry(property);
                ENTRY_CACHE.put(property, cached);
            }

            return cached;
        }
    }

    private static Entry buildEntry(IProperty<?> property) {
        Collection<?> allowed = property.getAllowedValues();
        int count = allowed.size();

        if (count == 0) {
            return null;
        }

        // Only the exact vanilla property classes get a closed-form index. A subclass may override
        // getAllowedValues() or parseValue() in ways that break the assumption, so anything else
        // falls through to an explicit value map.
        Class<?> type = property.getClass();

        if (type == PropertyBool.class && count == 2) {
            return new BooleanEntry(property, count);
        }

        if (type == PropertyEnum.class || type == PropertyDirection.class) {
            Object[] constants = property.getValueClass().getEnumConstants();

            // Ordinals are only dense when every constant is allowed; a filtered PropertyEnum
            // (BlockStone's variants, for instance) would leave holes in the bit range.
            if (constants != null && constants.length == count) {
                return new OrdinalEntry(property, count);
            }
        }

        if (type == PropertyInteger.class) {
            Entry contiguous = ContiguousIntegerEntry.tryCreate(property, allowed);

            if (contiguous != null) {
                return contiguous;
            }
        }

        return new MappedEntry(property, allowed);
    }

    // Rounds up to the next power of two by smearing the highest set bit down and adding one
    // Used to pad a property's value count out to a whole bit width, which is what makes the packed layout
    // addressable with shifts and masks instead of multiplications
    private static int ceilPowerOfTwo(int value) {
        int v = value - 1;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        return v + 1;
    }

    // One property's slice of the packed int: how wide it is, and how a value maps into it
    // Subclassed rather than branched so the hot indexOf call is a single virtual dispatch instead of a chain of
    // instanceof tests, and each subclass can use the cheapest indexing its property type allows
    abstract static class Entry {
        final IProperty<?> property;
        // Real number of allowed values
        final int count;
        // count rounded up to a power of two, i.e. how many slots the slice actually occupies
        final int bitSize;
        // Width of the slice in bits, log2 of bitSize
        final int bits;

        Entry(IProperty<?> property, int count) {
            this.property = property;
            this.count = count;
            this.bitSize = ceilPowerOfTwo(count);
            this.bits = Integer.numberOfTrailingZeros(this.bitSize);
        }

        // Index of value among the allowed values, or -1 when the value does not belong to this property
        // Never falls back to a default index: a wrong answer here produces a valid-looking but incorrect state
        abstract int indexOf(Object value);
    }

    private static final class BooleanEntry extends Entry {
        BooleanEntry(IProperty<?> property, int count) {
            super(property, count);
        }

        @Override
        int indexOf(Object value) {
            if (Boolean.TRUE.equals(value)) {
                return 1;
            }

            // Not a fall-through to 0: a non-Boolean here means someone passed the wrong property,
            // and answering "false" would silently return a valid-looking but wrong state.
            return Boolean.FALSE.equals(value) ? 0 : -1;
        }
    }

    private static final class OrdinalEntry extends Entry {
        private final Class<?> valueClass;

        OrdinalEntry(IProperty<?> property, int count) {
            super(property, count);
            this.valueClass = property.getValueClass();
        }

        @Override
        int indexOf(Object value) {
            return this.valueClass.isInstance(value) ? ((Enum<?>) value).ordinal() : -1;
        }
    }

    private static final class ContiguousIntegerEntry extends Entry {
        private final int minimum;

        private ContiguousIntegerEntry(IProperty<?> property, int count, int minimum) {
            super(property, count);
            this.minimum = minimum;
        }

        static ContiguousIntegerEntry tryCreate(IProperty<?> property, Collection<?> allowed) {
            int minimum = Integer.MAX_VALUE;
            int maximum = Integer.MIN_VALUE;

            for (Object value : allowed) {
                if (!(value instanceof Integer)) {
                    return null;
                }

                int i = (Integer) value;
                minimum = Math.min(minimum, i);
                maximum = Math.max(maximum, i);
            }

            // Contiguous iff the span matches the count; that also rules out duplicates.
            if (maximum - minimum + 1 != allowed.size()) {
                return null;
            }

            return new ContiguousIntegerEntry(property, allowed.size(), minimum);
        }

        @Override
        int indexOf(Object value) {
            if (!(value instanceof Integer)) {
                return -1;
            }

            int index = (Integer) value - this.minimum;
            return index >= 0 && index < this.count ? index : -1;
        }
    }

    // The general case, used when none of the closed-form entries above apply: an explicit value-to-index map
    // Keyed by equals rather than identity. Integer autoboxing only caches -128..127, so a property whose values
    // run past that range would miss on identity even for the same numeric value
    private static final class MappedEntry extends Entry {
        private final Object2IntOpenHashMap<Object> indices;

        MappedEntry(IProperty<?> property, Collection<?> allowed) {
            super(property, allowed.size());

            this.indices = new Object2IntOpenHashMap<>(allowed.size());
            this.indices.defaultReturnValue(-1);

            int index = 0;
            for (Object value : allowed) {
                this.indices.put(value, index++);
            }
        }

        @Override
        int indexOf(Object value) {
            return value == null ? -1 : this.indices.getInt(value);
        }
    }
}
