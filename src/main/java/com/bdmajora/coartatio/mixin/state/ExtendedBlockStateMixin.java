package com.bdmajora.coartatio.mixin.state;

import com.bdmajora.coartatio.state.CoartatioExtendedBlockState;
import com.bdmajora.coartatio.state.MappedStateOwner;
import com.bdmajora.coartatio.state.PropertyValueMapper;
import com.google.common.collect.ImmutableMap;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraftforge.common.property.ExtendedBlockState;
import net.minecraftforge.common.property.IUnlistedProperty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

// The unlisted-property counterpart to BlockStateContainerMixin
// ExtendedBlockState overrides createState, so the base mixin never fires for these containers — except in the
// one case where Forge delegates upward, when the block declared unlisted properties but the map came through
// empty. That case is deliberately left alone below so it falls through to super.createState and gets an
// ordinary packed state, which is what it should get
// Unlisted properties are the reason FoamFix needs an entire parallel container class. Here the mapper already
// lives on the container through MappedStateOwner, so this only has to pick which state class to build
@Mixin(ExtendedBlockState.class)
public abstract class ExtendedBlockStateMixin {
    @Inject(method = "createState", at = @At("HEAD"), cancellable = true, remap = false)
    private void coartatio$createPackedExtendedState(Block block,
                                                     ImmutableMap<IProperty<?>, Comparable<?>> properties,
                                                     ImmutableMap<IUnlistedProperty<?>, Optional<?>> unlistedProperties,
                                                     CallbackInfoReturnable<BlockStateContainer.StateImplementation> cir) {
        if (unlistedProperties == null || unlistedProperties.isEmpty()) {
            // Forge delegates to super here; BlockStateContainerMixin handles it.
            return;
        }

        // Forge's override means the base mixin's createState never runs for this container, so the
        // mapper is resolved here on first use instead.
        PropertyValueMapper mapper = ((MappedStateOwner) this).coartatio$mapper(block);

        if (mapper == null) {
            return;
        }

        cir.setReturnValue(new CoartatioExtendedBlockState(mapper, block, properties, unlistedProperties,
                coartatio$hasPresentValue(unlistedProperties)));
    }

    // Whether any unlisted value is already set, which the state uses as its initial dirty flag
    // States built during container setup normally have every unlisted value empty, but a mod is free to seed
    // one, and such a state has to start out dirty — otherwise the next listed-property change would hand its
    // unlisted values over to the shared clean instance
    @Unique
    private static boolean coartatio$hasPresentValue(ImmutableMap<IUnlistedProperty<?>, Optional<?>> unlisted) {
        for (Optional<?> value : unlisted.values()) {
            if (value.isPresent()) {
                return true;
            }
        }

        return false;
    }
}
