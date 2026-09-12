package com.bdmajora.coartatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.Arrays;
import java.util.List;

// A flattened AND of ORs, the shape of {"facing": "north|south", "half": "top|bottom"}, ported from Hydrogen
// Two arrays and a nested loop in place of a composite holding N SingleMatchAny objects
public final class AllMatchAnyObject implements Predicate<IBlockState> {
    // properties[i] is tested against the allowed set values[i]; the two arrays are always the same length and
    // are never handed out, so they can be treated as immutable
    private final IProperty<?>[] properties;
    private final Object[][] values;

    // Precomputed because these predicates are interned, so hashCode is called far more often than apply
    // deepHashCode, not hashCode, since values is an array of arrays
    private final int hash;

    private AllMatchAnyObject(IProperty<?>[] properties, Object[][] values) {
        this.properties = properties;
        this.values = values;
        this.hash = 31 * Arrays.hashCode(properties) + Arrays.deepHashCode(values);
    }

    // Flattens a list of conditions into this form, or returns null if any of them is not a SingleMatchAny
    // Null rather than an exception because the caller's job is to try each flattened shape in turn and fall
    // back to CompositePredicate when none fits
    public static AllMatchAnyObject tryFlatten(List<Predicate<IBlockState>> predicates) {
        int size = predicates.size();

        IProperty<?>[] properties = new IProperty<?>[size];
        Object[][] values = new Object[size][];

        for (int i = 0; i < size; i++) {
            Predicate<IBlockState> predicate = predicates.get(i);

            if (!(predicate instanceof SingleMatchAny)) {
                return null;
            }

            SingleMatchAny any = (SingleMatchAny) predicate;
            properties[i] = any.property;
            values[i] = any.values;
        }

        return new AllMatchAnyObject(properties, values);
    }

    // Every property must match one of its accepted values; fields are hoisted so the loop reads them once
    @Override
    public boolean apply(IBlockState state) {
        if (state == null) {
            return false;
        }

        // Hoisted into locals so the loop reads the fields once instead of on every iteration
        IProperty<?>[] properties = this.properties;
        Object[][] values = this.values;

        for (int i = 0; i < properties.length; i++) {
            Object actual = state.getValue(properties[i]);

            if (!contains(values[i], actual)) {
                return false;
            }
        }

        return true;
    }

    // Linear scan; the allowed sets are two or three entries, and property values are interned enum constants
    // or Boolean singletons, so the identity check almost always ends it before equals is reached
    private static boolean contains(Object[] candidates, Object actual) {
        for (Object candidate : candidates) {
            if (actual == candidate || actual.equals(candidate)) {
                return true;
            }
        }

        return false;
    }

    // Structural equality over both arrays, which is what lets the canonicalizer intern these
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AllMatchAnyObject)) {
            return false;
        }

        AllMatchAnyObject other = (AllMatchAnyObject) o;
        return Arrays.equals(this.properties, other.properties) && Arrays.deepEquals(this.values, other.values);
    }

    // Precomputed at construction; the predicate is immutable
    @Override
    public int hashCode() {
        return this.hash;
    }
}
