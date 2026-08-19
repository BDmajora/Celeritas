package com.bdmajora.coartatio.state;

import com.bdmajora.coartatio.Coartatio;
import com.bdmajora.coartatio.CoartatioConfig;
import com.bdmajora.coartatio.util.ClassDefineTool;
import com.google.common.collect.ImmutableMap;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replaces each block state's property {@code ImmutableMap} with a compact one that shares its key
 * array across every state of the block.
 *
 * <p>The map itself lives in {@code com.google.common.collect} — see
 * {@code CoartatioPropertyMap} for why, and {@link ClassDefineTool} for how it gets there. This
 * class is the bridge: it owns the reflective handle, decides whether a given map can use the shared
 * keys, and degrades to the original map whenever anything is not exactly as expected.
 *
 * <h2>Sharing the keys</h2>
 *
 * <p>Every state of a block is produced by the same loop over the same property set, so every state
 * carries the same keys in the same order and differs only in the values. The first state of a block
 * donates its key array; later states are checked against it and only share if the sequence matches
 * <i>by identity, in order</i>. A mismatch is not an error — that state simply keeps its original
 * map — which means an unusual {@code BlockStateContainer} costs nothing but a missed optimisation.
 */
public final class CompactPropertyMaps {
    private static final String MAP_CLASS = "com.google.common.collect.CoartatioPropertyMap";

    private static final AtomicInteger COMPACTED = new AtomicInteger();
    private static final AtomicInteger DECLINED = new AtomicInteger();

    private static Constructor<?> constructor;
    private static boolean initialised;

    private CompactPropertyMaps() {
    }

    /**
     * Attempts the class injection once. Safe to call repeatedly and safe to call before the config
     * says the feature is wanted — a failure here only means {@link #compact} becomes a no-op.
     */
    private static synchronized void initialise() {
        if (initialised) {
            return;
        }
        initialised = true;

        if (!CoartatioConfig.get().compactStateProperties) {
            return;
        }

        Class<?> defined = ClassDefineTool.defineClass(ImmutableMap.class, MAP_CLASS);

        if (defined == null) {
            Coartatio.LOGGER.info("Compact state property maps unavailable on this JVM; using Guava's");
            return;
        }

        try {
            constructor = defined.getConstructor(Object[].class, Object[].class);
            Coartatio.LOGGER.info("Compact state property maps enabled");
        } catch (NoSuchMethodException e) {
            Coartatio.LOGGER.warn("Injected {} has no usable constructor", MAP_CLASS, e);
        }
    }

    /**
     * @param sharedKeys the block's shared key array, from {@link PropertyValueMapper#sharedKeys}
     * @return a compact equivalent of {@code original}, or {@code original} itself
     */
    public static ImmutableMap<net.minecraft.block.properties.IProperty<?>, Comparable<?>> compact(
            Object[] sharedKeys,
            ImmutableMap<net.minecraft.block.properties.IProperty<?>, Comparable<?>> original) {
        initialise();

        if (constructor == null || sharedKeys == null) {
            DECLINED.incrementAndGet();
            return original;
        }

        Object[] values = new Object[sharedKeys.length];
        int index = 0;

        for (Map.Entry<net.minecraft.block.properties.IProperty<?>, Comparable<?>> entry : original.entrySet()) {
            // Guarded rather than assumed: if the iteration order ever diverges from the shared key
            // array, the values would silently line up against the wrong properties.
            if (index >= sharedKeys.length || entry.getKey() != sharedKeys[index]) {
                DECLINED.incrementAndGet();
                return original;
            }

            values[index++] = entry.getValue();
        }

        if (index != sharedKeys.length) {
            DECLINED.incrementAndGet();
            return original;
        }

        try {
            @SuppressWarnings("unchecked")
            ImmutableMap<net.minecraft.block.properties.IProperty<?>, Comparable<?>> compacted =
                    (ImmutableMap<net.minecraft.block.properties.IProperty<?>, Comparable<?>>)
                            constructor.newInstance(sharedKeys, values);

            COMPACTED.incrementAndGet();
            return compacted;
        } catch (ReflectiveOperationException | RuntimeException e) {
            DECLINED.incrementAndGet();
            return original;
        }
    }

    /** Property maps replaced with the compact implementation. */
    public static long compacted() {
        return COMPACTED.get();
    }

    public static String statistics() {
        if (COMPACTED.get() == 0 && DECLINED.get() == 0) {
            return "unused";
        }

        return String.format("%d compacted, %d left on Guava", COMPACTED.get(), DECLINED.get());
    }
}
