package com.bdmajora.coarctatio.state;

import com.bdmajora.coarctatio.CoarctatioConfig;
import com.bdmajora.coarctatio.dedup.DeduplicationCache;
import com.bdmajora.coarctatio.state.predicate.AllMatchAnyObject;
import com.bdmajora.coarctatio.state.predicate.AllMatchOne;
import com.bdmajora.coarctatio.state.predicate.AllMatchOneBoolean;
import com.bdmajora.coarctatio.state.predicate.CompositePredicate;
import com.bdmajora.coarctatio.state.predicate.NegatedPredicate;
import com.bdmajora.coarctatio.state.predicate.SingleMatchAny;
import com.bdmajora.coarctatio.state.predicate.SingleMatchOne;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.multipart.ICondition;

import java.util.ArrayList;
import java.util.List;

// Builds and interns the Predicate<IBlockState> objects behind multipart definitions; flattening turns Guava composites into one object over arrays, and interning shares it since flattened predicates compare equal by construction
public final class ConditionCanonicalizer {
    // Matches vanilla ConditionPropertyValue.SPLITTER; re-declared to avoid shadowing a private static.
    private static final Splitter VALUE_SPLITTER = Splitter.on('|').omitEmptyStrings();

    private static final DeduplicationCache<Predicate<IBlockState>> POOL =
            new DeduplicationCache<>("Multipart predicates", CoarctatioConfig.get().poolSizeLimit);

    private ConditionCanonicalizer() {
    }

    // Arms the pool for a bake
    public static void open() {
        POOL.open();
    }

    // Drops the pool index after a bake; the shared predicates themselves live on in the models
    public static void close() {
        POOL.close();
    }

    // Predicates this pool prevented allocating a second copy of.
    public static long sharedCount() {
        return POOL.shared();
    }

    // Hit and miss counts for /coarctatio
    public static String statistics() {
        return POOL.toString();
    }

    // Replacement for ConditionPropertyValue.getPredicate; error messages mirror vanilla's wording since a malformed blockstate file is a pack-authoring bug
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

    // Replacement for ConditionAnd.getPredicate, trying specialisations most-specific first (Hydrogen's order): all-boolean AND, all-single-value AND, AND of multi-valued tests, else a composite of interned children
    public static Predicate<IBlockState> all(List<Predicate<IBlockState>> predicates) {
        if (predicates.size() == 1) {
            return predicates.get(0);
        }

        Predicate<IBlockState> flattened = AllMatchOneBoolean.tryFlatten(predicates);

        if (flattened == null) {
            flattened = AllMatchOne.tryFlatten(predicates);
        }

        if (flattened == null) {
            flattened = AllMatchAnyObject.tryFlatten(predicates);
        }

        if (flattened == null) {
            flattened = CompositePredicate.all(predicates);
        }

        return intern(flattened);
    }

    // Replacement for ConditionOr.getPredicate.
    public static Predicate<IBlockState> any(List<Predicate<IBlockState>> predicates) {
        if (predicates.size() == 1) {
            return predicates.get(0);
        }

        return intern(CompositePredicate.any(predicates));
    }

    // Resolves each child to its interned predicate, preserving order rather than sorting by hash like FerriteCore, since a mod ICondition may be order-sensitive and a wrong render beats a missed share
    public static List<Predicate<IBlockState>> resolve(Iterable<? extends ICondition> conditions,
                                                       BlockStateContainer container) {
        List<Predicate<IBlockState>> resolved = new ArrayList<>();

        for (ICondition condition : conditions) {
            resolved.add(condition.getPredicate(container));
        }

        return resolved;
    }

    // Parses one value token; an unknown value is a broken blockstate file, so it throws like vanilla
    private static Object parseValue(BlockStateContainer container, IProperty<?> property, String raw, String original) {
        Optional<?> parsed = property.parseValue(raw);

        if (!parsed.isPresent()) {
            throw new RuntimeException(container + ": has an unknown value: " + original);
        }

        return parsed.get();
    }

    // Returns the pooled instance, or registers this one if it is the first
    private static Predicate<IBlockState> intern(Predicate<IBlockState> predicate) {
        return POOL.deduplicate(predicate);
    }
}
