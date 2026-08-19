package com.bdmajora.coartatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.Arrays;
import java.util.List;

/**
 * A flattened {@code AND} where every condition tests a boolean property.
 *
 * <p>Ported from Hydrogen's class of the same name. This is the single most common multipart shape
 * in the game — {@code {"north": "true", "east": "false"}} on every fence, wall, pane, redstone wire
 * and pipe — so it is worth a specialisation over the general {@link AllMatchOne}.
 *
 * <p>The win is a {@code boolean[]} instead of an {@code Object[]} of boxed {@code Boolean}s: one
 * byte per property rather than a four-byte reference, and {@code test} compares primitives with no
 * chance of falling through to {@code equals}.
 */
public final class AllMatchOneBoolean implements Predicate<IBlockState> {
    private final IProperty<?>[] properties;
    private final boolean[] values;

    private final int hash;

    private AllMatchOneBoolean(IProperty<?>[] properties, boolean[] values) {
        this.properties = properties;
        this.values = values;
        this.hash = 31 * Arrays.hashCode(properties) + Arrays.hashCode(values);
    }

    /**
     * @return a flattened predicate, or {@code null} unless every input is a {@link SingleMatchOne}
     *         over a {@code Boolean} value.
     */
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
