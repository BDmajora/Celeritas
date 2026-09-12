package com.bdmajora.coartatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.state.IBlockState;

import java.util.Arrays;
import java.util.List;

// Array-backed AND/OR over arbitrary children: the fallback for trees no flattened form can express
// Still worth having, since Guava's composite retains the ICondition tree and its container for the model's lifetime
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

    // One loop covering both modes: a child result that disagrees with requireAll settles the whole thing
    // AND (requireAll true): the first false returns false. OR (requireAll false): the first true returns true
    // Falling out of the loop means nothing disagreed, so the answer is requireAll itself — which also gives the
    // right empty-list behaviour, AND of nothing being true and OR of nothing being false
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
