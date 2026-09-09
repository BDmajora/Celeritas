package com.bdmajora.coartatio.state;

import com.bdmajora.coartatio.Coartatio;
import com.bdmajora.coartatio.CoartatioConfig;
import com.bdmajora.coartatio.util.ClassDefineTool;
import com.google.common.collect.ImmutableMap;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

// Replaces each block state's property ImmutableMap with a compact one sharing its key array across
// every state of the block. The map class itself lives in com.google.common.collect (see ClassDefineTool
// for how it's injected there); this class owns the reflective handle and falls back to the original map
// whenever the key sequence doesn't match by identity and order (not an error, just a missed optimisation).
public final class CompactPropertyMaps {
    private static final String MAP_CLASS = "com.google.common.collect.CoartatioPropertyMap";

    private static final AtomicInteger COMPACTED = new AtomicInteger();
    private static final AtomicInteger DECLINED = new AtomicInteger();

    private static Constructor<?> constructor;
    private static boolean initialised;

    private CompactPropertyMaps() {
    }

    // Attempts the class injection once. Safe to call repeatedly or before config wants the feature;
    // a failure here just means compact() becomes a no-op.
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
            return;
        }

        try {
            constructor = defined.getConstructor(Object[].class, Object[].class);
        } catch (NoSuchMethodException e) {
            Coartatio.LOGGER.warn("Injected {} has no usable constructor", MAP_CLASS, e);
        }
    }

    // sharedKeys comes from PropertyValueMapper#sharedKeys; returns a compact equivalent of original, or original itself.
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

    // Property maps replaced with the compact implementation.
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
