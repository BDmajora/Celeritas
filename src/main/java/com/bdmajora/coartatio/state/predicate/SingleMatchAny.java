package com.bdmajora.coartatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.Arrays;

// property=a|b|c — one property against a set of accepted values
// Vanilla builds the same thing as a Predicates.or over one anonymous inner class per value: a composite
// object, a transformed Iterable, and N closures. This is one object and one array
// property and values are public because AllMatchAnyObject.tryFlatten reads them straight out when folding a
// list of these into its own arrays
public final class SingleMatchAny implements Predicate<IBlockState> {
    public final IProperty<?> property;
    public final Object[] values;

    private final int hash;

    public SingleMatchAny(IProperty<?> property, Object[] values) {
        this.property = property;
        this.values = values;
        this.hash = 31 * property.hashCode() + Arrays.hashCode(values);
    }

    @Override
    public boolean apply(IBlockState state) {
        if (state == null) {
            return false;
        }

        Object actual = state.getValue(this.property);

        for (Object value : this.values) {
            if (actual == value || actual.equals(value)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SingleMatchAny)) {
            return false;
        }

        SingleMatchAny other = (SingleMatchAny) o;
        return (this.property == other.property || this.property.equals(other.property))
                && Arrays.equals(this.values, other.values);
    }

    @Override
    public int hashCode() {
        return this.hash;
    }
}
