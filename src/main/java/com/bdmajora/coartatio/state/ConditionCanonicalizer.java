package com.bdmajora.coartatio.state;

import com.bdmajora.coartatio.CoartatioConfig;
import com.bdmajora.coartatio.dedup.DeduplicationCache;
import com.bdmajora.coartatio.state.predicate.AllMatchOne;
import com.bdmajora.coartatio.state.predicate.CompositePredicate;
import com.bdmajora.coartatio.state.predicate.NegatedPredicate;
import com.bdmajora.coartatio.state.predicate.SingleMatchAny;
import com.bdmajora.coartatio.state.predicate.SingleMatchOne;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.multipart.ICondition;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds and interns the {@code Predicate<IBlockState>} objects behind multipart blockstate
 * definitions.
 *
 * <p>Two independent savings stack here:
 *
 * <ul>
 *   <li><b>Flattening</b> (from Hydrogen) turns a tree of Guava composites and anonymous classes into
 *       a single object holding arrays.
 *   <li><b>Interning</b> (from FerriteCore, by way of LoliASM's {@code CanonicalConditions}) shares
 *       one instance between every selector that tests the same thing. {@code facing=north} appears
 *       in hundreds of blockstate files; without interning each gets its own closure, each of which
 *       captures the property and the value.
 * </ul>
 *
 * <p>Interning is what makes flattening pay off twice: identical flattened predicates compare equal
 * by construction, whereas the anonymous classes vanilla generates never can.
 *
 * <p>The pool is bake-scoped. Predicates handed out stay shared for the lifetime of the models that
 * hold them; only the index is dropped when the reload ends.
 */
public final class ConditionCanonicalizer {
    /** Matches vanilla {@code ConditionPropertyValue.SPLITTER}; re-declared to avoid shadowing a private static. */
    private static final Splitter VALUE_SPLITTER = Splitter.on('|').omitEmptyStrings();

    private static final DeduplicationCache<Predicate<IBlockState>> POOL =
            new DeduplicationCache<>("Multipart predicates", CoartatioConfig.get().poolSizeLimit);

    private ConditionCanonicalizer() {
    }

    public static void open() {
        POOL.open();
    }

    public static void close() {
        POOL.close();
    }

    public static String statistics() {
        return POOL.toString();
    }

    /**
     * Replacement for {@code ConditionPropertyValue.getPredicate}.
     *
     * <p>Error messages intentionally mirror vanilla's wording: a malformed blockstate file is a
     * pack-authoring bug, and the person reading the crash should not have to know Coartatio exists.
     */
    public static Predicate<IBlockState> propertyValue(BlockStateContainer container, String key, String value) {
        IProperty<?> property = container.getProperty(key);

        if (property == null) {
            throw new RuntimeException("{key=" + key + ", value=" + value + "}: Definition: "
                    + container + " has no property: " + key);
        }

        String remaining = value;
        boolean negate = !remaining.isEmpty() && remaining.charAt(0) == '!';

        if (negate) {
            remaining = remaining.substring(1);
        }

        List<String> parts = VALUE_SPLITTER.splitToList(remaining);

        if (parts.isEmpty()) {
            throw new RuntimeException("{key=" + key + ", value=" + value + "}: has an empty value: " + value);
        }

        Predicate<IBlockState> predicate;

        if (parts.size() == 1) {
            predicate = intern(new SingleMatchOne(property, parseValue(container, property, parts.get(0), value)));
        } else {
            Object[] values = new Object[parts.size()];

            for (int i = 0; i < values.length; i++) {
                values[i] = parseValue(container, property, parts.get(i), value);
            }

            predicate = intern(new SingleMatchAny(property, values));
        }

        return negate ? intern(new NegatedPredicate(predicate)) : predicate;
    }

    /** Replacement for {@code ConditionAnd.getPredicate}. */
    public static Predicate<IBlockState> all(List<Predicate<IBlockState>> predicates) {
        if (predicates.size() == 1) {
            return predicates.get(0);
        }

        Predicate<IBlockState> flattened = AllMatchOne.tryFlatten(predicates);

        if (flattened == null) {
            flattened = CompositePredicate.all(predicates);
        }

        return intern(flattened);
    }

    /** Replacement for {@code ConditionOr.getPredicate}. */
    public static Predicate<IBlockState> any(List<Predicate<IBlockState>> predicates) {
        if (predicates.size() == 1) {
            return predicates.get(0);
        }

        return intern(CompositePredicate.any(predicates));
    }

    /**
     * Resolves each child condition to its (already interned) predicate.
     *
     * <p>Order is preserved rather than sorted. FerriteCore sorts by hash to widen the intern pool,
     * but a mod-supplied {@code ICondition} is free to be order-sensitive or to have side effects,
     * and a wrong render is a worse outcome than a missed share.
     */
    public static List<Predicate<IBlockState>> resolve(Iterable<? extends ICondition> conditions,
                                                       BlockStateContainer container) {
        List<Predicate<IBlockState>> resolved = new ArrayList<>();

        for (ICondition condition : conditions) {
            resolved.add(condition.getPredicate(container));
        }

        return resolved;
    }

    private static Object parseValue(BlockStateContainer container, IProperty<?> property, String raw, String original) {
        Optional<?> parsed = property.parseValue(raw);

        if (!parsed.isPresent()) {
            throw new RuntimeException(container + ": has an unknown value: " + original);
        }

        return parsed.get();
    }

    private static Predicate<IBlockState> intern(Predicate<IBlockState> predicate) {
        return POOL.deduplicate(predicate);
    }
}
