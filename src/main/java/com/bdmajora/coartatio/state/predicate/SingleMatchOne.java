package com.bdmajora.coartatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.Objects;

// property=value — the leaf of every multipart condition tree
// property and value are public because the tryFlatten methods on the AllMatch* classes read them directly
public final class SingleMatchOne implements Predicate<IBlockState> {
    public final IProperty<?> property;
    public final Object value;

    // Computed once in the constructor rather than on demand
    // PropertyEnum.hashCode folds in the whole allowed value set, which is expensive enough that FoamFix patches
    // it outright; paying for it once per predicate instead of once per pool lookup keeps it off the bake's
    // critical path, and these predicates are hashed constantly while being interned
    private final int hash;

    public SingleMatchOne(IProperty<?> property, Object value) {
        this.property = property;
        this.value = value;
        this.hash = 31 * property.hashCode() + Objects.hashCode(value);
    }

    @Override
    public boolean apply(IBlockState state) {
        if (state == null) {
            return false;
        }

        Object actual = state.getValue(this.property);
        return actual == this.value || actual.equals(this.value);
    }

    // Properties are compared by equals rather than identity, so two blocks that each declared their own
    // PropertyBool.create("north") end up sharing one interned predicate
    // That is safe because IBlockState.getValue resolves through an ImmutableMap keyed the same way: a state
    // looks up an equal property and finds its own value no matter which instance was passed in
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SingleMatchOne)) {
            return false;
        }

        SingleMatchOne other = (SingleMatchOne) o;
        return (this.property == other.property || this.property.equals(other.property))
                && Objects.equals(this.value, other.value);
    }

    @Override
    public int hashCode() {
        return this.hash;
    }
}
