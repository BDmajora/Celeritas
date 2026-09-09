package com.bdmajora.coartatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.Arrays;
import java.util.List;

// A flattened AND over conditions that are all plain property=value tests
// Vanilla builds this as a Guava AndPredicate wrapping a transformed Iterable wrapping N anonymous classes:
// five or six objects per selector, times every selector, times every multipart block
// Here it is one object holding two exactly-sized arrays, and apply is a tight loop with no virtual dispatch
// per element. Ported from Hydrogen's AllMatchOneObject
// AllMatchOneBoolean handles the all-boolean case, which is commoner still; this is the general version
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

    @Override
    public int hashCode() {
        return this.hash;
    }
}
