package com.bdmajora.coartatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.Arrays;
import java.util.List;

/**
 * A flattened {@code AND} where every condition is a multi-valued test — {@code AND} of {@code OR}s.
 *
 * <p>Ported from Hydrogen's class of the same name. Written out, this is the shape of
 * {@code {"facing": "north|south", "half": "top|bottom"}}: without flattening it is one composite
 * holding N {@link SingleMatchAny} objects, and with it, two arrays and a nested loop.
 *
 * <p>Rarer than {@link AllMatchOneBoolean}, but it costs one class to remove a level of indirection
 * from every multipart lookup that has this shape, and multipart lookups run per block per chunk
 * rebuild.
 */
public final class AllMatchAnyObject implements Predicate<IBlockState> {
    private final IProperty<?>[] properties;
    private final Object[][] values;

    private final int hash;

    private AllMatchAnyObject(IProperty<?>[] properties, Object[][] values) {
        this.properties = properties;
        this.values = values;
        this.hash = 31 * Arrays.hashCode(properties) + Arrays.deepHashCode(values);
    }

    /** @return a flattened predicate, or {@code null} unless every input is a {@link SingleMatchAny}. */
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

    @Override
    public boolean apply(IBlockState state) {
        if (state == null) {
            return false;
        }

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

    private static boolean contains(Object[] candidates, Object actual) {
        for (Object candidate : candidates) {
            if (actual == candidate || actual.equals(candidate)) {
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
        if (!(o instanceof AllMatchAnyObject)) {
            return false;
        }

        AllMatchAnyObject other = (AllMatchAnyObject) o;
        return Arrays.equals(this.properties, other.properties) && Arrays.deepEquals(this.values, other.values);
    }

    @Override
    public int hashCode() {
        return this.hash;
    }
}
