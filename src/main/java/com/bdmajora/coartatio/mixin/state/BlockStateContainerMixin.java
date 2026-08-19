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

/**
 * Swaps vanilla's table-backed block states for {@link CoartatioBlockState}.
 *
 * <p>{@code createState} is a Forge addition — it exists exactly so a state implementation can be
 * substituted, which is why this needs no coremod and no access transformer. It is also why the
 * injection carries {@code remap = false}: methods Forge adds by patch are not in the obfuscation
 * map and keep their source names.
 *
 * <p>Cancelling at {@code HEAD} leaves the vanilla path fully intact underneath. If the mapper
 * declines this block — blacklisted, too many states, or an {@code IProperty} it cannot index — the
 * injection simply returns and vanilla builds the state it always would. That is the whole fallback
 * strategy, and it is per-block rather than global.
 *
 * <p>Note that a mod shipping its own {@code BlockStateContainer} subclass which overrides
 * {@code createState} without calling {@code super} never reaches this code, so its blocks keep
 * their own states automatically.
 */
@Mixin(BlockStateContainer.class)
public abstract class BlockStateContainerMixin implements MappedStateOwner {
    @Unique
    private PropertyValueMapper coartatio$stateMapper;

    @Unique
    private boolean coartatio$stateMapperResolved;

    /**
     * The container calls {@code createState} once per state, so the mapper is built on the first
     * call and reused. Safe here because the container assigns its property map before the loop that
     * produces states.
     */
    @Override
    public PropertyValueMapper coartatio$mapper(Block block) {
        if (!this.coartatio$stateMapperResolved) {
            this.coartatio$stateMapperResolved = true;
            this.coartatio$stateMapper = PropertyValueMapper.create((BlockStateContainer) (Object) this, block);
        }

        return this.coartatio$stateMapper;
    }

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
