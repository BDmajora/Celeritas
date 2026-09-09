package com.bdmajora.coartatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.Arrays;
import java.util.List;

// A flattened AND where every condition tests a boolean property
// Ported from Hydrogen's class of the same name
// This is the single most common multipart shape in the game — {"north": "true", "east": "false"} on every
// fence, wall, pane, redstone wire and pipe — which is what makes it worth specialising over AllMatchOne
// The saving is a boolean[] rather than an Object[] of boxed Booleans: one byte per property instead of a
// reference, and the comparison is a primitive one that can never fall through to equals
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

    // Flattens a list of conditions into this form, or returns null unless every one of them is a SingleMatchOne
    // whose value is a Boolean
    // The two rejections are separate on purpose: a non-SingleMatchOne cannot be flattened at all, while a
    // SingleMatchOne over a non-Boolean is still flattenable, just by AllMatchOne instead
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

            // A property that is boolean-valued for one block is boolean-valued for all of them, so
            // this cast is safe for any state this predicate can legitimately be applied to.
            if (!(actual instanceof Boolean) || (Boolean) actual != values[i]) {
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
        if (!(o instanceof AllMatchOneBoolean)) {
            return false;
        }

        AllMatchOneBoolean other = (AllMatchOneBoolean) o;
        return Arrays.equals(this.properties, other.properties) && Arrays.equals(this.values, other.values);
    }

    @Override
    public int hashCode() {
        return this.hash;
    }
}
