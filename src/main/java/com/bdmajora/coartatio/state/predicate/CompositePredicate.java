package com.bdmajora.coartatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.state.IBlockState;

import java.util.Arrays;
import java.util.List;

/**
 * Array-backed {@code AND}/{@code OR} over arbitrary predicates — the fallback for condition trees
 * that {@link AllMatchOne} cannot flatten (nested {@code AND} inside {@code OR}, negations, mod-added
 * {@code ICondition} implementations).
 *
 * <p>Still worth having over Guava's equivalents: {@code Predicates.and} keeps the transformed
 * {@code Iterable} it was handed alive, which in turn keeps the whole {@code ICondition} tree and its
 * {@code BlockStateContainer} reference alive for the lifetime of the baked model.
 */
public final class CompositePredicate implements Predicate<IBlockState> {
    private final Predicate<IBlockState>[] predicates;
    private final boolean requireAll;

    private final int hash;

    private CompositePredicate(Predicate<IBlockState>[] predicates, boolean requireAll) {
        this.predicates = predicates;
        this.requireAll = requireAll;
        this.hash = 31 * Arrays.hashCode(predicates) + (requireAll ? 1 : 0);
    }

    @SuppressWarnings("unchecked")
    public static CompositePredicate all(List<Predicate<IBlockState>> predicates) {
        return new CompositePredicate(predicates.toArray(new Predicate[0]), true);
    }

    @SuppressWarnings("unchecked")
    public static CompositePredicate any(List<Predicate<IBlockState>> predicates) {
        return new CompositePredicate(predicates.toArray(new Predicate[0]), false);
    }

    @Override
    public boolean apply(IBlockState state) {
        for (Predicate<IBlockState> predicate : this.predicates) {
            if (predicate.apply(state) != this.requireAll) {
                return !this.requireAll;
            }
        }

        return this.requireAll;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CompositePredicate)) {
            return false;
        }

        CompositePredicate other = (CompositePredicate) o;
        return this.requireAll == other.requireAll && Arrays.equals(this.predicates, other.predicates);
    }

    @Override
    public int hashCode() {
        return this.hash;
    }
}
