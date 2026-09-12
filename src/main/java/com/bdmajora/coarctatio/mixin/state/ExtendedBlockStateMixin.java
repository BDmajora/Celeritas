package com.bdmajora.coarctatio.mixin.state;

import com.bdmajora.coarctatio.state.CoarctatioExtendedBlockState;
import com.bdmajora.coarctatio.state.MappedStateOwner;
import com.bdmajora.coarctatio.state.PropertyValueMapper;
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

// Unlisted-property counterpart to BlockStateContainerMixin: ExtendedBlockState overrides createState, so the base mixin only fires when Forge delegates upward on an empty unlisted map, and that case is left to fall through; the mapper lives on the container via MappedStateOwner
@Mixin(ExtendedBlockState.class)
public abstract class ExtendedBlockStateMixin {
    @Inject(method = "createState", at = @At("HEAD"), cancellable = true, remap = false)
    private void coarctatio$createPackedExtendedState(Block block,
                                                     ImmutableMap<IProperty<?>, Comparable<?>> properties,
                                                     ImmutableMap<IUnlistedProperty<?>, Optional<?>> unlistedProperties,
                                                     CallbackInfoReturnable<BlockStateContainer.StateImplementation> cir) {
        if (unlistedProperties == null || unlistedProperties.isEmpty()) {
            // Forge delegates to super here; BlockStateContainerMixin handles it.
            return;
        }

        // Forge's override means the base mixin's createState never runs here, so the mapper is resolved on first use
        PropertyValueMapper mapper = ((MappedStateOwner) this).coarctatio$mapper(block);

        if (mapper == null) {
            return;
        }

        cir.setReturnValue(new CoarctatioExtendedBlockState(mapper, block, properties, unlistedProperties,
                coarctatio$hasPresentValue(unlistedProperties)));
    }

    // Whether any unlisted value is already set, the state's initial dirty flag; a mod may seed one, and such a state must start dirty or the next listed change hands its unlisted values to the shared clean instance
    @Unique
    private static boolean coarctatio$hasPresentValue(ImmutableMap<IUnlistedProperty<?>, Optional<?>> unlisted) {
        for (Optional<?> value : unlisted.values()) {
            if (value.isPresent()) {
                return true;
            }
        }

        return false;
    }
}
