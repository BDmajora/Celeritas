package com.bdmajora.coarctatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.state.IBlockState;

import java.util.Arrays;
import java.util.List;

// Array-backed AND/OR over arbitrary children, the fallback for unflattenable trees; still drops the ICondition tree and container Guava's composite retains
public final class CompositePredicate implements Predicate<IBlockState> {
    private final Predicate<IBlockState>[] predicates;
    // true = AND, false = OR. One field instead of two subclasses, so apply below is written once
    private final boolean requireAll;

    private final int hash;

    private CompositePredicate(Predicate<IBlockState>[] predicates, boolean requireAll) {
        this.predicates = predicates;
        this.requireAll = requireAll;
        this.hash = 31 * Arrays.hashCode(predicates) + (requireAll ? 1 : 0);
    }

    // toArray(new Predicate[0]) copies, so the caller's list can be reused or mutated afterwards
    @SuppressWarnings("unchecked")
    public static CompositePredicate all(List<Predicate<IBlockState>> predicates) {
        return new CompositePredicate(predicates.toArray(new Predicate[0]), true);
    }

    // OR form; short-circuits on the first match
    @SuppressWarnings("unchecked")
    public static CompositePredicate any(List<Predicate<IBlockState>> predicates) {
        return new CompositePredicate(predicates.toArray(new Predicate[0]), false);
    }

    // One loop for both modes: a child disagreeing with requireAll settles it, and falling out returns requireAll itself (AND of nothing true, OR of nothing false)
    @Override
    public boolean apply(IBlockState state) {
        for (Predicate<IBlockState> predicate : this.predicates) {
            if (predicate.apply(state) != this.requireAll) {
                return !this.requireAll;
            }
        }

        return this.requireAll;
    }

    // Structural equality over the children and the AND/OR flag
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

    // Precomputed at construction; the predicate is immutable
    @Override
    public int hashCode() {
        return this.hash;
    }
}
