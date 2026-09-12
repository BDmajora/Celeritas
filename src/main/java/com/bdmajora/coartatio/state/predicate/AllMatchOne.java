package com.bdmajora.coartatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.Arrays;
import java.util.List;

// A flattened AND over plain property=value tests: one object and two arrays where vanilla builds five or six
// objects per selector. Ported from Hydrogen; AllMatchOneBoolean handles the commoner all-boolean case
public final class AllMatchOne implements Predicate<IBlockState> {
    private final IProperty<?>[] properties;
    private final Object[] values;

    private final int hash;

    private AllMatchOne(IProperty<?>[] properties, Object[] values) {
        this.properties = properties;
        this.values = values;
        this.hash = 31 * Arrays.hashCode(properties) + Arrays.hashCode(values);
    }

    // Flattens a list of conditions into this form, or returns null unless every one is a SingleMatchOne
    public static AllMatchOne tryFlatten(List<Predicate<IBlockState>> predicates) {
        int size = predicates.size();

        IProperty<?>[] properties = new IProperty<?>[size];
        Object[] values = new Object[size];

        for (int i = 0; i < size; i++) {
            Predicate<IBlockState> predicate = predicates.get(i);

            if (!(predicate instanceof SingleMatchOne)) {
                return null;
            }

            SingleMatchOne single = (SingleMatchOne) predicate;
            properties[i] = single.property;
            values[i] = single.value;
        }

        return new AllMatchOne(properties, values);
    }

    // Every property must equal its expected value; fields are hoisted so the loop reads them once
    @Override
    public boolean apply(IBlockState state) {
        if (state == null) {
            return false;
        }

        // Hoisted into locals so the loop does not re-read the fields each iteration
        IProperty<?>[] properties = this.properties;
        Object[] values = this.values;

        for (int i = 0; i < properties.length; i++) {
            Object actual = state.getValue(properties[i]);

            // Identity first: property values are interned enum constants and Boolean singletons, so equals is
            // effectively never reached and is only kept for correctness against odd mod-added property types
            if (actual != values[i] && !actual.equals(values[i])) {
                return false;
            }
        }

        return true;
    }

    // Structural equality over both arrays, which is what lets the canonicalizer intern these
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AllMatchOne)) {
            return false;
        }

        AllMatchOne other = (AllMatchOne) o;
        return Arrays.equals(this.properties, other.properties) && Arrays.equals(this.values, other.values);
    }

    // Precomputed at construction; the predicate is immutable
    @Override
    public int hashCode() {
        return this.hash;
    }
}
