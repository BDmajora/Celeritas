package com.bdmajora.coarctatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.Arrays;
import java.util.List;

// A flattened AND where every condition tests a boolean property, the commonest multipart shape (fences, walls, panes, wire); a boolean[] compared as primitives
public final class AllMatchOneBoolean implements Predicate<IBlockState> {
    private final IProperty<?>[] properties;
    // Parallel to properties: the value that property must have for the condition to hold
    private final boolean[] values;

    // Precomputed; these predicates are interned, so hashCode runs far more often than apply
    private final int hash;

    private AllMatchOneBoolean(IProperty<?>[] properties, boolean[] values) {
        this.properties = properties;
        this.values = values;
        this.hash = 31 * Arrays.hashCode(properties) + Arrays.hashCode(values);
    }

    // Flattens into this form, or null unless every condition is a SingleMatchOne over a Boolean; a non-Boolean SingleMatchOne is still flattenable by AllMatchOne instead
    public static AllMatchOneBoolean tryFlatten(List<Predicate<IBlockState>> predicates) {
        int size = predicates.size();

        IProperty<?>[] properties = new IProperty<?>[size];
        boolean[] values = new boolean[size];

        for (int i = 0; i < size; i++) {
            Predicate<IBlockState> predicate = predicates.get(i);

            if (!(predicate instanceof SingleMatchOne)) {
                return null;
            }

            SingleMatchOne single = (SingleMatchOne) predicate;

            if (!(single.value instanceof Boolean)) {
                return null;
            }

            properties[i] = single.property;
            values[i] = (Boolean) single.value;
        }

        return new AllMatchOneBoolean(properties, values);
    }

    // Every boolean property must equal its expected value; fields are hoisted so the loop reads them once
    @Override
    public boolean apply(IBlockState state) {
        if (state == null) {
            return false;
        }

        // Hoisted into locals so the loop does not re-read the fields each iteration
        IProperty<?>[] properties = this.properties;
        boolean[] values = this.values;

        for (int i = 0; i < properties.length; i++) {
            Object actual = state.getValue(properties[i]);

            // A property boolean-valued for one block is boolean-valued for all, so the cast is safe for any state this predicate can legitimately see
            if (!(actual instanceof Boolean) || (Boolean) actual != values[i]) {
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
        if (!(o instanceof AllMatchOneBoolean)) {
            return false;
        }

        AllMatchOneBoolean other = (AllMatchOneBoolean) o;
        return Arrays.equals(this.properties, other.properties) && Arrays.equals(this.values, other.values);
    }

    // Precomputed at construction; the predicate is immutable
    @Override
    public int hashCode() {
        return this.hash;
    }
}
