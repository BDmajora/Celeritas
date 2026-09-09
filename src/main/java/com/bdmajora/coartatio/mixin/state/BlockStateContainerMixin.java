package com.bdmajora.coartatio.mixin.state;

import com.bdmajora.coartatio.state.CoartatioBlockState;
import com.bdmajora.coartatio.state.MappedStateOwner;
import com.bdmajora.coartatio.state.PropertyValueMapper;
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

// Swaps vanilla's table-backed block states for CoartatioBlockState
// createState is a Forge addition that exists precisely so a state implementation can be substituted, which is
// why this needs no coremod and no access transformer
// Cancelling at HEAD leaves the vanilla path completely intact underneath: when the mapper declines a block —
// blacklisted, too many states, or an IProperty it cannot index — the injection just returns and vanilla builds
// the state it always would. That is the entire fallback strategy, and it is per block rather than global
// A mod shipping its own BlockStateContainer subclass that overrides createState without calling super never
// reaches this code at all, so its blocks keep their own states automatically
@Mixin(BlockStateContainer.class)
public abstract class BlockStateContainerMixin implements MappedStateOwner {
    // Null both before resolution and when this block was declined, hence the separate resolved flag
    @Unique
    private PropertyValueMapper coartatio$stateMapper;

    // Distinguishes "not built yet" from "built and came back null", so a declined block is not re-analysed once
    // per state
    @Unique
    private boolean coartatio$stateMapperResolved;

    // The container calls createState once per state, so the mapper is built on the first call and reused
    // Safe to build it here because the container assigns its property map before the loop that produces states
    @Override
    public PropertyValueMapper coartatio$mapper(Block block) {
        if (!this.coartatio$stateMapperResolved) {
            this.coartatio$stateMapperResolved = true;
            this.coartatio$stateMapper = PropertyValueMapper.create((BlockStateContainer) (Object) this, block);
        }

        return this.coartatio$stateMapper;
    }

    // remap = false because methods Forge adds by patch are not in the obfuscation map and keep their source
    // names
    @Inject(method = "createState", at = @At("HEAD"), cancellable = true, remap = false)
    private void coartatio$createPackedState(Block block,
                                             ImmutableMap<IProperty<?>, Comparable<?>> properties,
                                             ImmutableMap<IUnlistedProperty<?>, Optional<?>> unlistedProperties,
                                             CallbackInfoReturnable<BlockStateContainer.StateImplementation> cir) {
        PropertyValueMapper mapper = coartatio$mapper(block);

        if (mapper == null) {
            return;
        }

        cir.setReturnValue(new CoartatioBlockState(mapper, block, properties));
    }
}
