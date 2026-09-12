package com.bdmajora.coarctatio.state;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableTable;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;

import java.util.Map;

// Block state carrying a packed int (see PropertyValueMapper) instead of a property-value table; produced via Forge's createState hook, so mods overriding it are unaffected
public class CoarctatioBlockState extends BlockStateContainer.StateImplementation {
    protected final PropertyValueMapper mapper;

    // This state's packed index, assigned in buildPropertyValueTable().
    protected int value;

    public CoarctatioBlockState(PropertyValueMapper mapper, Block block,
                               ImmutableMap<IProperty<?>, Comparable<?>> properties) {
        // Compacted before super since the field is final in StateImplementation; CompactPropertyMaps returns the original when the injection is unavailable or the map shape is unexpected
        super(block, CompactPropertyMaps.compact(mapper.sharedKeys(properties), properties));
        this.mapper = mapper;
    }

    // For states synthesised at runtime (only CoarctatioExtendedBlockState), which never go through buildPropertyValueTable() since they are not in the cartesian product
    protected CoarctatioBlockState(PropertyValueMapper mapper, Block block,
                                  ImmutableMap<IProperty<?>, Comparable<?>> properties, int value) {
        super(block, properties);
        this.mapper = mapper;
        this.value = value;
    }

    // Registers with the mapper instead of building an ImmutableTable, turning a table-per-state into one array entry per state
    @Override
    public void buildPropertyValueTable(
            Map<Map<IProperty<?>, Comparable<?>>, BlockStateContainer.StateImplementation> map) {
        this.value = this.mapper.register(this);
    }

    // Mask-and-index through the mapper instead of a table lookup; returns this when the value is unchanged
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
            // Unreachable unless an IProperty reports different allowed values on different calls, leaving holes in the cartesian product
            throw new IllegalStateException("Impetus/Coarctatio: no state registered for "
                    + Block.REGISTRY.getNameForObject(getBlock()) + " at packed index " + packed
                    + " while setting " + property + " to " + newValue
                    + ". Please report this with the mod list.");
        }

        return state;
    }

    // Lazily rebuilds the property-value table for states someone queries (FoamFix returns null and crashes mods reading via IBlockProperties); stored in the inherited field for direct access, and unsynchronised publish is safe since ImmutableTable is all-final
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

    // Builds the same exception text vanilla would, so callers catching on the message keep working
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
