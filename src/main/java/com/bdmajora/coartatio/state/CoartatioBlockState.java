package com.bdmajora.coartatio.state;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableTable;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;

import java.util.Map;

/**
 * A block state that carries a packed {@code int} instead of a property-value table.
 *
 * <p>See {@link PropertyValueMapper} for the layout. The two overrides that matter are
 * {@link #buildPropertyValueTable}, which registers the state with the mapper instead of building a
 * table, and {@link #withProperty}, which becomes a mask plus an array index.
 *
 * <p>Instances are produced from {@code BlockStateContainer.createState}, which Forge added as an
 * extension point precisely so that a state implementation can be swapped out. Nothing here replaces
 * the container itself, so a mod with its own {@code BlockStateContainer} subclass that overrides
 * {@code createState} keeps its own states and is unaffected.
 */
public class CoartatioBlockState extends BlockStateContainer.StateImplementation {
    protected final PropertyValueMapper mapper;

    /** This state's packed index. Assigned in {@link #buildPropertyValueTable}. */
    protected int value;

    public CoartatioBlockState(PropertyValueMapper mapper, Block block,
                               ImmutableMap<IProperty<?>, Comparable<?>> properties) {
        // Compacted before the super call, because the field is final in StateImplementation and
        // cannot be swapped afterwards. CompactPropertyMaps hands back the original untouched
        // whenever the class injection is unavailable or the map's shape is not what it expects.
        super(block, CompactPropertyMaps.compact(mapper.sharedKeys(properties), properties));
        this.mapper = mapper;
    }

    /**
     * Constructor for states synthesised at runtime rather than during container setup — currently
     * only {@link CoartatioExtendedBlockState}, whose unlisted-property states are not part of the
     * container's cartesian product and therefore never get {@link #buildPropertyValueTable} called.
     */
    protected CoartatioBlockState(PropertyValueMapper mapper, Block block,
                                  ImmutableMap<IProperty<?>, Comparable<?>> properties, int value) {
        super(block, properties);
        this.mapper = mapper;
        this.value = value;
    }

    /**
     * Registers with the mapper instead of building an {@code ImmutableTable}. This is the call that
     * deletes the memory: the container invokes it on every state once they all exist, and what
     * would have been a table per state becomes one array entry per state.
     */
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

    /**
     * Rebuilds the property-value table on demand.
     *
     * <p>FoamFix leaves this returning {@code null}, which breaks any mod that reads the table
     * directly — a real and recurring source of crashes, because the accessor is public API on
     * Forge's {@code IBlockProperties}. Reconstructing it costs exactly the memory vanilla would have
     * spent, but only for the states somebody actually asks about, which in practice is a handful.
     *
     * <p>The result is stored into the inherited field so that code reaching the field directly
     * (Forge's own {@code ExtendedStateImplementation} does) sees it too. Racing threads may both
     * build it; the loser's table is discarded and the winner's is safe to publish unsynchronised,
     * because {@code ImmutableTable} has only final fields.
     */
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
