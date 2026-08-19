package com.bdmajora.coartatio.state.predicate;

import com.google.common.base.Predicate;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.Objects;

/**
 * {@code property=value}. The leaf of every multipart condition tree.
 *
 * <p>Properties are compared by {@code equals}, not identity, so that two blocks which each declare
 * their own {@code PropertyBool.create("north")} share one predicate. That is safe because
 * {@code IBlockState.getValue} resolves through an {@code ImmutableMap} keyed the same way — a state
 * looks up an equal property and finds its own value regardless of which instance was passed in.
 *
 * <p>The hash is computed once and stored. {@code PropertyEnum.hashCode} folds in the whole allowed
 * value set, which is expensive enough that FoamFix patches it outright; paying for it once per
 * predicate instead of once per pool lookup keeps that off the bake's critical path.
 */
public final class SingleMatchOne implements Predicate<IBlockState> {
    public final IProperty<?> property;
    public final Object value;

    private final int hash;

    public SingleMatchOne(IProperty<?> property, Object value) {
        this.property = property;
        this.value = value;
        this.hash = 31 * property.hashCode() + Objects.hashCode(value);
    }

    @Override
    public boolean apply(IBlockState state) {
        if (state == null) {
            return false;
        }

        Object actual = state.getValue(this.property);
        return actual == this.value || actual.equals(this.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SingleMatchOne)) {
            return false;
        }

        SingleMatchOne other = (SingleMatchOne) o;
        return (this.property == other.property || this.property.equals(other.property))
                && Objects.equals(this.value, other.value);
    }

    @Override
    public int hashCode() {
        return this.hash;
    }
}
