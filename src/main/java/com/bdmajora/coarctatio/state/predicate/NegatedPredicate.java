package com.bdmajora.coarctatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.state.IBlockState;

// property=!value, inverting whatever it wraps; Guava's Predicates.not only defines equals against its own type, so owning the wrapper keeps the tree poolable
public final class NegatedPredicate implements Predicate<IBlockState> {
    // The predicate being inverted; interned itself, so this wrapper interns too
    private final Predicate<IBlockState> delegate;

    // Takes ownership of an already-canonical delegate
    public NegatedPredicate(Predicate<IBlockState> delegate) {
        this.delegate = delegate;
    }

    // Single virtual call plus a negate; no allocation on the hot model path
    @Override
    public boolean apply(IBlockState state) {
        return !this.delegate.apply(state);
    }

    // Identity of the delegate is what makes two negations interchangeable
    @Override
    public boolean equals(Object o) {
        return o instanceof NegatedPredicate && this.delegate.equals(((NegatedPredicate) o).delegate);
    }

    // Bitwise complement: one instruction, and a negation can never collide with the predicate it wraps
    @Override
    public int hashCode() {
        return ~this.delegate.hashCode();
    }
}
