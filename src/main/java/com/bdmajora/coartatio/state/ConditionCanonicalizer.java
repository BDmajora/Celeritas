package com.bdmajora.coartatio.state;

import com.bdmajora.coartatio.CoartatioConfig;
import com.bdmajora.coartatio.dedup.DeduplicationCache;
import com.bdmajora.coartatio.state.predicate.AllMatchAnyObject;
import com.bdmajora.coartatio.state.predicate.AllMatchOne;
import com.bdmajora.coartatio.state.predicate.AllMatchOneBoolean;
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

// Builds and interns the Predicate<IBlockState> objects behind multipart blockstate definitions
// Flattening turns Guava composites into one object over arrays; interning then shares that object between
// every selector testing the same thing, which works because flattened predicates compare equal by construction
public final class ConditionCanonicalizer {
    // Matches vanilla ConditionPropertyValue.SPLITTER; re-declared to avoid shadowing a private static.
    private static final Splitter VALUE_SPLITTER = Splitter.on('|').omitEmptyStrings();

    private static final DeduplicationCache<Predicate<IBlockState>> POOL =
            new DeduplicationCache<>("Multipart predicates", CoartatioConfig.get().poolSizeLimit);

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

    // Hit and miss counts for /coartatio
    public static String statistics() {
        return POOL.toString();
    }

    // Replacement for ConditionPropertyValue.getPredicate. Error messages intentionally mirror vanilla's
    // wording: a malformed blockstate file is a pack-authoring bug, not something that should mention Coartatio.
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

    // Replacement for ConditionAnd.getPredicate. Tries specialisations most-specific first (Hydrogen's
    // StatePropertyPredicateHelper order): all-boolean AND -> primitive array, all-single-value AND -> object
    // array, AND of multi-valued tests -> jagged array, else falls back to a composite of interned children.
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

    // Resolves each child condition to its (already interned) predicate. Order is preserved rather than
    // sorted (FerriteCore sorts by hash to widen the pool) since a mod ICondition may be order-sensitive
    // or have side effects, and a wrong render is worse than a missed share.
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
