package com.bdmajora.coartatio.state;

import com.google.common.collect.ImmutableMap;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraftforge.common.property.IExtendedBlockState;
import net.minecraftforge.common.property.IUnlistedProperty;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The packed-state equivalent of Forge's {@code ExtendedStateImplementation}.
 *
 * <p>Note this <i>implements</i> {@code IExtendedBlockState} rather than extending Forge's class:
 * {@code ExtendedStateImplementation} is {@code protected static}, and inheriting from it would need
 * an access transformer and would drag in its table-based {@code withProperty}. Implementing the
 * interface on top of {@link CoartatioBlockState} keeps one packed-state code path.
 *
 * <h2>Clean versus dirty states</h2>
 *
 * <p>A block with unlisted properties still has an ordinary cartesian product of <i>listed</i>
 * states, and those are the ones the container builds and the mapper registers. They are "clean":
 * every unlisted value is {@code Optional.empty()}.
 *
 * <p>Setting an unlisted property produces a "dirty" state, created on the fly and never registered
 * with the mapper. It still carries the packed index of the clean state it came from, which is what
 * makes {@link #getClean()} and listed-property changes O(1) rather than a search. Clearing the last
 * unlisted value returns the registered clean instance, so identity comparisons against
 * {@code getBaseState()} keep working.
 *
 * <p>This mirrors Forge's own contract, where {@code cleanState} plays the same role — the
 * difference is that Forge threads the clean state's {@code ImmutableTable} through every dirty
 * state, and we thread an {@code int}.
 */
public class CoartatioExtendedBlockState extends CoartatioBlockState implements IExtendedBlockState {
    private final ImmutableMap<IUnlistedProperty<?>, Optional<?>> unlistedProperties;
    private final boolean dirty;

    public CoartatioExtendedBlockState(PropertyValueMapper mapper, Block block,
                                       ImmutableMap<IProperty<?>, Comparable<?>> properties,
                                       ImmutableMap<IUnlistedProperty<?>, Optional<?>> unlistedProperties,
                                       boolean dirty) {
        super(mapper, block, properties);
        this.unlistedProperties = unlistedProperties;
        this.dirty = dirty;
    }

    private CoartatioExtendedBlockState(PropertyValueMapper mapper, Block block,
                                        ImmutableMap<IProperty<?>, Comparable<?>> properties,
                                        ImmutableMap<IUnlistedProperty<?>, Optional<?>> unlistedProperties,
                                        boolean dirty, int value) {
        super(mapper, block, properties, value);
        this.unlistedProperties = unlistedProperties;
        this.dirty = dirty;
    }

    @Override
    public <T extends Comparable<T>, V extends T> IBlockState withProperty(IProperty<T> property, V newValue) {
        IBlockState clean = super.withProperty(property, newValue);

        // A clean state can hand back the registered instance directly. A dirty one has to carry its
        // unlisted values across, so it re-wraps the clean state's properties and packed index.
        if (!this.dirty || clean == this) {
            return clean;
        }

        return new CoartatioExtendedBlockState(this.mapper, getBlock(), clean.getProperties(),
                this.unlistedProperties, true, ((CoartatioBlockState) clean).value);
    }

    @Override
    public <V> IExtendedBlockState withProperty(IUnlistedProperty<V> property, V newValue) {
        Optional<?> current = this.unlistedProperties.get(property);

        if (current == null) {
            throw new IllegalArgumentException("Cannot set unlisted property " + property
                    + " as it does not exist in " + getBlock().getBlockState());
        }

        if (Objects.equals(current.orElse(null), newValue)) {
            return this;
        }

        if (!property.isValid(newValue)) {
            throw new IllegalArgumentException("Cannot set unlisted property " + property + " to " + newValue
                    + " on block " + Block.REGISTRY.getNameForObject(getBlock()) + ", it is not an allowed value");
        }

        boolean anyPresent = false;
        ImmutableMap.Builder<IUnlistedProperty<?>, Optional<?>> builder = ImmutableMap.builder();

        for (Map.Entry<IUnlistedProperty<?>, Optional<?>> entry : this.unlistedProperties.entrySet()) {
            IUnlistedProperty<?> key = entry.getKey();
            Optional<?> value = key.equals(property) ? Optional.ofNullable(newValue) : entry.getValue();

            anyPresent |= value.isPresent();
            builder.put(key, value);
        }

        if (!anyPresent) {
            // Back to fully clean: return the registered instance so that identity comparisons and
            // the mapper's array stay authoritative.
            return (IExtendedBlockState) getClean();
        }

        return new CoartatioExtendedBlockState(this.mapper, getBlock(), getProperties(),
                builder.build(), true, this.value);
    }

    @Override
    public Collection<IUnlistedProperty<?>> getUnlistedNames() {
        return this.unlistedProperties.keySet();
    }

    @Override
    public <V> V getValue(IUnlistedProperty<V> property) {
        Optional<?> value = this.unlistedProperties.get(property);

        if (value == null) {
            throw new IllegalArgumentException("Cannot get unlisted property " + property
                    + " as it does not exist in " + getBlock().getBlockState());
        }

        return property.getType().cast(value.orElse(null));
    }

    @Override
    public ImmutableMap<IUnlistedProperty<?>, Optional<?>> getUnlistedProperties() {
        return this.unlistedProperties;
    }

    @Override
    public IBlockState getClean() {
        return this.dirty ? this.mapper.byValue(this.value) : this;
    }
}
