package com.bdmajora.coartatio.state;

import com.bdmajora.coartatio.Coartatio;
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

/**
 * Packs every listed property of a block into the bits of a single {@code int}, so that a block state
 * needs one field instead of a table.
 *
 * <h2>What this replaces</h2>
 *
 * <p>Vanilla gives every {@code StateImplementation} its own
 * {@code ImmutableTable<IProperty, Comparable, IBlockState>} mapping "this property set to that value"
 * onto the resulting state. For a block with {@code P} properties averaging {@code V} values, that is
 * {@code P * (V - 1)} table cells <i>per state</i>, and there are {@code V^P} states. The table is the
 * single largest structure in a modded heap — routinely 250-400 MB across a 300-mod pack, and it is
 * pure redundancy: every state's table is derivable from the block's property set.
 *
 * <p>Here each property is assigned a contiguous bit range, wide enough to index its allowed values.
 * A state is then an index into one {@code IBlockState[]} shared by every state of the block, and
 * {@code withProperty} becomes mask-and-index instead of a hash lookup in a per-state table. The
 * table disappears entirely; what remains is one array per block plus one {@code int} per state.
 *
 * <p>The technique is FoamFix's, and FerriteCore later did the same thing on modern versions. This
 * port differs in three ways that matter:
 *
 * <ul>
 *   <li><b>Value indices are resolved through the block's own property instance.</b> FoamFix keys its
 *       per-property value tables by identity, so passing an equal-but-distinct {@code IProperty} —
 *       which vanilla accepts, because the state's property map is {@code equals}-keyed — silently
 *       builds a second mapping with a possibly different ordering. Here the property name resolves
 *       to this block's entry, and the value is then looked up in that entry.
 *   <li><b>Bounded allocation.</b> Bit ranges are padded to powers of two, so the state array can be
 *       larger than the state count. FoamFix only rejects a block past 31 bits, which permits a
 *       multi-gigabyte array. This rejects anything over {@link #MAX_STATE_ARRAY} slots and falls
 *       back to vanilla for that block.
 *   <li><b>The table can still be produced on demand.</b> See
 *       {@link CoartatioBlockState#getPropertyValueTable()}.
 * </ul>
 *
 * <h2>Layout</h2>
 *
 * <p>Properties are ordered by how much of their bit range they waste, least first. That puts the
 * worst-fitting property in the high bits, where its unused range can be trimmed off the end of the
 * array rather than multiplying through every lower property.
 */
public final class PropertyValueMapper {
    /**
     * Hard ceiling on the shared state array. A block needing more than a million slots is
     * pathological; falling back to vanilla for it costs a table we were never going to fit anyway.
     */
    private static final int MAX_STATE_ARRAY = 1 << 20;

    /** Entries are per-property and immutable, so blocks sharing a static property share one. */
    private static final Map<IProperty<?>, Entry> ENTRY_CACHE = new Reference2ObjectOpenHashMap<>();

    private static final AtomicInteger BLOCKS_MAPPED = new AtomicInteger();
    private static final AtomicInteger BLOCKS_SKIPPED = new AtomicInteger();
    static final AtomicInteger TABLES_MATERIALISED = new AtomicInteger();

    /** Least wasteful property first; the worst fit ends up in the high bits where it can be trimmed. */
    private static final Comparator<Entry> BY_BIT_FITNESS = (a, b) -> {
        int wasteA = a.bitSize - a.count;
        int wasteB = b.bitSize - b.count;

        return wasteA == wasteB
                ? a.property.getName().compareTo(b.property.getName())
                : Integer.compare(wasteA, wasteB);
    };

    private final Entry[] entries;
    private final int[] offsets;
    private final Object2IntOpenHashMap<String> indexByName;
    private final IBlockState[] states;

    private PropertyValueMapper(Entry[] entries, int[] offsets, Object2IntOpenHashMap<String> indexByName,
                                IBlockState[] states) {
        this.entries = entries;
        this.offsets = offsets;
        this.indexByName = indexByName;
        this.states = states;
    }

    /**
     * Builds a mapper for a container, or returns {@code null} if this block should keep vanilla
     * states.
     *
     * <p>Safe to call from inside {@code BlockStateContainer}'s constructor: the property map is
     * assigned before the state loop that calls {@code createState}.
     */
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
            Coartatio.LOGGER.debug("Leaving {} on vanilla states: would need {} slots", block, size);
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

    /**
     * Assigns this state its packed index and files it in the shared array.
     *
     * <p>Called from {@code buildPropertyValueTable}, which the container invokes on every state once
     * they all exist — so by the time anything can call {@link #byValue} the array is complete.
     */
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
        return value;
    }

    /** The state at a packed index, or {@code null} if that combination was never registered. */
    public IBlockState byValue(int value) {
        return this.states[value];
    }

    /**
     * Returns {@code packed} with {@code property} set to {@code newValue}, or {@code -1} if this
     * block has no such property or the value is not allowed.
     */
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

        int offset = this.offsets[index];
        int mask = (entry.bitSize - 1) << offset;

        return (packed & ~mask) | (valueIndex << offset);
    }

    public static String statistics() {
        return String.format("%d blocks packed, %d left on vanilla states, %d tables rebuilt on demand",
                BLOCKS_MAPPED.get(), BLOCKS_SKIPPED.get(), TABLES_MATERIALISED.get());
    }

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

    private static int ceilPowerOfTwo(int value) {
        int v = value - 1;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        return v + 1;
    }

    /** One property's slice of the packed int: how wide it is, and how a value maps into it. */
    abstract static class Entry {
        final IProperty<?> property;
        final int count;
        final int bitSize;
        final int bits;

        Entry(IProperty<?> property, int count) {
            this.property = property;
            this.count = count;
            this.bitSize = ceilPowerOfTwo(count);
            this.bits = Integer.numberOfTrailingZeros(this.bitSize);
        }

        /** @return the index of {@code value} within the allowed values, or {@code -1}. */
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

    /**
     * The general case: an explicit value-to-index map.
     *
     * <p>Keyed by {@code equals}, not identity. Integer autoboxing only caches -128..127, so a
     * property whose values exceed that range would miss on identity even for the same numeric
     * value.
     */
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
