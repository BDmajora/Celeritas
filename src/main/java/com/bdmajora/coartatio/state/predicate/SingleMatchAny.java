package com.bdmajora.coartatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.Arrays;

// property=a|b|c, one property against a set of accepted values: one object and one array where vanilla
// builds a composite, a transformed Iterable and N closures. Fields are public for AllMatchAnyObject to fold
public final class SingleMatchAny implements Predicate<IBlockState> {
    public final IProperty<?> property;
    public final Object[] values;

    private final int hash;

    public SingleMatchAny(IProperty<?> property, Object[] values) {
        this.property = property;
        this.values = values;
        this.hash = 31 * property.hashCode() + Arrays.hashCode(values);
    }

    // Linear scan of the accepted values; sets here are two or three long
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

    // Structural equality, which is what lets the canonicalizer intern these
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

    // Precomputed at construction; the predicate is immutable
    @Override
    public int hashCode() {
        return this.hash;
    }
}
