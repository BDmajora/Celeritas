package com.bdmajora.coarctatio.mixin.state;

import com.bdmajora.coarctatio.state.CoarctatioBlockState;
import com.bdmajora.coarctatio.state.MappedStateOwner;
import com.bdmajora.coarctatio.state.PropertyValueMapper;
import com.google.common.collect.ImmutableMap;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraftforge.common.property.IUnlistedProperty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

// Swaps vanilla's table-backed states for CoarctatioBlockState through Forge's createState hook (no coremod, no AT); cancelling at HEAD means a declined block (blacklisted, too many states, unindexable IProperty) just gets vanilla's state, per block
@Mixin(BlockStateContainer.class)
public abstract class BlockStateContainerMixin implements MappedStateOwner {
    // Null both before resolution and when this block was declined, hence the separate resolved flag
    @Unique
    private PropertyValueMapper coarctatio$stateMapper;

    // Distinguishes "not built yet" from "built and came back null", so a declined block is not re-analysed once per state
    @Unique
    private boolean coarctatio$stateMapperResolved;

    // createState is called once per state, so the mapper is built on the first call; safe because the container assigns its property map before the state loop
    @Override
    public PropertyValueMapper coarctatio$mapper(Block block) {
        if (!this.coarctatio$stateMapperResolved) {
            this.coarctatio$stateMapperResolved = true;
            this.coarctatio$stateMapper = PropertyValueMapper.create((BlockStateContainer) (Object) this, block);
        }

        return this.coarctatio$stateMapper;
    }

    // remap = false because methods Forge adds by patch are not in the obfuscation map and keep their source names
    @Inject(method = "createState", at = @At("HEAD"), cancellable = true, remap = false)
    private void coarctatio$createPackedState(Block block,
                                             ImmutableMap<IProperty<?>, Comparable<?>> properties,
                                             ImmutableMap<IUnlistedProperty<?>, Optional<?>> unlistedProperties,
                                             CallbackInfoReturnable<BlockStateContainer.StateImplementation> cir) {
        PropertyValueMapper mapper = coarctatio$mapper(block);

        if (mapper == null) {
            return;
        }

        cir.setReturnValue(new CoarctatioBlockState(mapper, block, properties));
    }
}
