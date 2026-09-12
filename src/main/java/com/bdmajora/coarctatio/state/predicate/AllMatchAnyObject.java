package com.bdmajora.coarctatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.Arrays;
import java.util.List;

// A flattened AND of ORs, the shape of {"facing": "north|south", "half": "top|bottom"} (from Hydrogen): two arrays and a nested loop instead of a composite of N SingleMatchAny objects
public final class AllMatchAnyObject implements Predicate<IBlockState> {
    // properties[i] is tested against the allowed set values[i]; same length, never handed out, treated as immutable
    private final IProperty<?>[] properties;
    private final Object[][] values;

    // Precomputed because interned predicates are hashed far more than applied; deepHashCode since values is an array of arrays
    private final int hash;

    private AllMatchAnyObject(IProperty<?>[] properties, Object[][] values) {
        this.properties = properties;
        this.values = values;
        this.hash = 31 * Arrays.hashCode(properties) + Arrays.deepHashCode(values);
    }

    // Flattens conditions into this form, or null if any is not a SingleMatchAny so the caller can try the next shape and fall back to CompositePredicate
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

    // Linear scan; allowed sets are two or three interned enum constants or Boolean singletons, so the identity check almost always ends it
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
