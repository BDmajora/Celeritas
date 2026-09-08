package com.bdmajora.coartatio.state;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableTable;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;

import java.util.Map;

// Block state that carries a packed int (see PropertyValueMapper) instead of a property-value table.
// Produced via BlockStateContainer.createState, a Forge extension point, so mods with their own createState override are unaffected.
public class CoartatioBlockState extends BlockStateContainer.StateImplementation {
    protected final PropertyValueMapper mapper;

    // This state's packed index, assigned in buildPropertyValueTable().
    protected int value;

    public CoartatioBlockState(PropertyValueMapper mapper, Block block,
                               ImmutableMap<IProperty<?>, Comparable<?>> properties) {
        // Compacted before the super call, because the field is final in StateImplementation and
        // cannot be swapped afterwards. CompactPropertyMaps hands back the original untouched
        // whenever the class injection is unavailable or the map's shape is not what it expects.
        super(block, CompactPropertyMaps.compact(mapper.sharedKeys(properties), properties));
        this.mapper = mapper;
    }

    // For states synthesised at runtime (only CoartatioExtendedBlockState) rather than by container setup;
    // these never go through buildPropertyValueTable() since they aren't part of the cartesian product.
    protected CoartatioBlockState(PropertyValueMapper mapper, Block block,
                                  ImmutableMap<IProperty<?>, Comparable<?>> properties, int value) {
        super(block, properties);
        this.mapper = mapper;
        this.value = value;
    }

    // Registers with the mapper instead of building an ImmutableTable; called on every state once the
    // container has built them all, turning a table-per-state into one array entry per state.
    @Override
    public void buildPropertyValueTable(
            Map<Map<IProperty<?>, Comparable<?>>, BlockStateContainer.StateImplementation> map) {
        this.value = this.mapper.register(this);
    }

    @Override
    public <T extends Comparable<T>, V extends T> IBlockState withProperty(IProperty<T> property, V newValue) {
        int packed = this.mapper.withValue(this.value, property, newValue);

        if (packed == this.value) {
            return this;
        }

        if (packed < 0) {
            throw describeFailure(property, newValue);
        }

        IBlockState state = this.mapper.byValue(packed);

        if (state == null) {
            // Unreachable unless an IProperty reports different allowed values on different calls,
            // which would have left holes in the container's cartesian product.
            throw new IllegalStateException("Impetus/Coartatio: no state registered for "
                    + Block.REGISTRY.getNameForObject(getBlock()) + " at packed index " + packed
                    + " while setting " + property + " to " + newValue
                    + ". Please report this with the mod list.");
        }

        return state;
    }

    // Rebuilds the property-value table on demand, lazily, only for states someone actually queries.
    // FoamFix returns null here, which crashes mods reading the table via Forge's public IBlockProperties accessor.
    // Stored into the inherited field so direct field access (Forge's ExtendedStateImplementation) still sees it;
    // racing threads may both build it, but ImmutableTable is all-final so publishing unsynchronised is safe.
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ImmutableTable<IProperty<?>, Comparable<?>, IBlockState> getPropertyValueTable() {
        ImmutableTable<IProperty<?>, Comparable<?>, IBlockState> table = this.propertyValueTable;

        if (table != null) {
            return table;
        }

        PropertyValueMapper.TABLES_MATERIALISED.incrementAndGet();

        ImmutableTable.Builder<IProperty<?>, Comparable<?>, IBlockState> builder = ImmutableTable.builder();

        for (Map.Entry<IProperty<?>, Comparable<?>> entry : getProperties().entrySet()) {
            IProperty<?> property = entry.getKey();

            for (Comparable<?> allowed : (Iterable<Comparable<?>>) (Iterable) property.getAllowedValues()) {
                if (allowed == entry.getValue()) {
                    continue;
                }

                int packed = this.mapper.withValue(this.value, property, allowed);

                if (packed >= 0) {
                    IBlockState state = this.mapper.byValue(packed);

                    if (state != null) {
                        builder.put(property, allowed, state);
                    }
                }
            }
        }

        table = builder.build();
        this.propertyValueTable = table;

        return table;
    }

    private IllegalArgumentException describeFailure(IProperty<?> property, Object newValue) {
        if (!getProperties().containsKey(property)) {
            return new IllegalArgumentException("Cannot set property " + property
                    + " as it does not exist in " + getBlock().getBlockState());
        }

        return new IllegalArgumentException("Cannot set property " + property + " to " + newValue
                + " on block " + Block.REGISTRY.getNameForObject(getBlock())
                + ", it is not an allowed value");
    }
}
