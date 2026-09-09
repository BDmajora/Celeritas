package com.bdmajora.coartatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.state.IBlockState;

// property=!value — inverts whatever it wraps
// Guava's Predicates.not would work and even defines equals, but only against other Guava NotPredicates, so a
// negation of one of the flattened predicates here could never be interned alongside them
// Owning the wrapper is what keeps the whole condition tree poolable
public final class NegatedPredicate implements Predicate<IBlockState> {
    private final Predicate<IBlockState> delegate;

    public NegatedPredicate(Predicate<IBlockState> delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean apply(IBlockState state) {
        return !this.delegate.apply(state);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NegatedPredicate && this.delegate.equals(((NegatedPredicate) o).delegate);
    }

    // Bitwise complement rather than a multiply-and-add: it is one instruction, and it guarantees a negation
    // never collides with the predicate it wraps
    @Override
    public int hashCode() {
        return ~this.delegate.hashCode();
    }
}
