package com.bdmajora.coartatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.Arrays;
import java.util.List;

/**
 * A flattened {@code AND} over conditions that are all plain {@code property=value} tests.
 *
 * <p>This is the shape that actually shows up in the wild — {@code {"north": "true", "up": "false"}}
 * on every fence, wall, pane, wire and pipe in the game. Vanilla represents it as a Guava
 * {@code AndPredicate} wrapping a transformed {@code Iterable} wrapping N anonymous classes: five or
 * six objects per selector, times every selector, times every multipart block.
 *
 * <p>Here it is one object holding two exactly-sized arrays, and {@code apply} is a tight loop with
 * no virtual dispatch per element. Ported from Hydrogen's {@code AllMatchOneObject}.
 */
public final class AllMatchOne implements Predicate<IBlockState> {
    private final IProperty<?>[] properties;
    private final Object[] values;

    private final int hash;

    private AllMatchOne(IProperty<?>[] properties, Object[] values) {
        this.properties = properties;
        this.values = values;
        this.hash = 31 * Arrays.hashCode(properties) + Arrays.hashCode(values);
    }

    /** @return a flattened predicate, or {@code null} if the input is not all {@link SingleMatchOne}. */
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

        IProperty<?>[] properties = this.properties;
        Object[] values = this.values;

        for (int i = 0; i < properties.length; i++) {
            Object actual = state.getValue(properties[i]);

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
