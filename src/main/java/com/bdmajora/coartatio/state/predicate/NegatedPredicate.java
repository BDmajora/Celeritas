package com.bdmajora.coartatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.state.IBlockState;

/**
 * {@code property=!value}.
 *
 * <p>Guava's {@code Predicates.not} would do, and it even defines {@code equals} — but only against
 * other Guava {@code NotPredicate}s, so a negation of one of our flattened predicates could never be
 * interned alongside them. Owning the wrapper keeps the whole tree poolable.
 */
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

    @Override
    public int hashCode() {
        return ~this.delegate.hashCode();
    }
}
