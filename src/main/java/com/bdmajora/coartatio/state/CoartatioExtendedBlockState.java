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

// Packed-state equivalent of Forge's ExtendedStateImplementation.
// Implements IExtendedBlockState rather than extending Forge's class, since that class is protected static
// and its withProperty is table-based; this keeps one packed-state code path via CoartatioBlockState.
//
// "Clean" states are the ordinary listed-property cartesian product the container builds and the mapper
// registers (all unlisted values Optional.empty()). Setting an unlisted property produces a "dirty" state,
// created on the fly and never registered, but still carrying the clean state's packed index — that's what
// makes getClean() and listed-property changes O(1). Mirrors Forge's own cleanState, but threads an int
// instead of an ImmutableTable.
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
            // Back to fully clean: return the registered instance so identity comparisons stay valid.
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
