package com.bdmajora.coarctatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.Objects;

// property=value, the leaf of every multipart condition tree; fields public because the AllMatch* tryFlatten methods read them
public final class SingleMatchOne implements Predicate<IBlockState> {
    public final IProperty<?> property;
    public final Object value;

    // Computed once in the constructor: PropertyEnum.hashCode folds in the whole allowed value set (FoamFix patches it outright), and interned predicates are hashed constantly
    private final int hash;

    public SingleMatchOne(IProperty<?> property, Object value) {
        this.property = property;
        this.value = value;
        this.hash = 31 * property.hashCode() + Objects.hashCode(value);
    }

    // One property against one expected value
    @Override
    public boolean apply(IBlockState state) {
        if (state == null) {
            return false;
        }

        Object actual = state.getValue(this.property);
        return actual == this.value || actual.equals(this.value);
    }

    // Properties compared by equals so two blocks each declaring PropertyBool.create("north") share one predicate; safe because IBlockState.getValue resolves through an ImmutableMap keyed the same way
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

    // Precomputed at construction; the predicate is immutable
    @Override
    public int hashCode() {
        return this.hash;
    }
}
