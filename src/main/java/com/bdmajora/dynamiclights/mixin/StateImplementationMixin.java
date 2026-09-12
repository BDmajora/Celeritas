package com.bdmajora.dynamiclights.mixin;

import com.bdmajora.dynamiclights.DynamicLights;
import com.bdmajora.impetus.impl.util.EmptyBlockAccess;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Folds dynamic light into the terrain lightmap coordinate the chunk builder asks each state for (LightDataCache#compute), on worker threads hence the engine's read/write lock; opaque cubes are skipped since brightening a solid interior breaks AO, and emitters are exempt
@SideOnly(Side.CLIENT)
@Mixin(BlockStateContainer.StateImplementation.class)
public abstract class StateImplementationMixin {
    @Inject(method = "getPackedLightmapCoords", at = @At("RETURN"), cancellable = true)
    private void impetus$addDynamicLight(IBlockAccess source, BlockPos pos,
                                         CallbackInfoReturnable<Integer> cir) {
        if (!DynamicLights.options().mode.isEnabled()) {
            return;
        }

        // LightDataCache probes every state against an empty world to detect emissives; that is not a world position, so iterating sources for it is pure waste on the chunk-build hot path
        if (source == EmptyBlockAccess.INSTANCE) {
            return;
        }

        IBlockState state = (IBlockState) (Object) this;
        if (state.isOpaqueCube() && state.getLightValue(source, pos) == 0) {
            return;
        }

        cir.setReturnValue(DynamicLights.engine().getLightmapWithDynamicLight(pos, cir.getReturnValueI()));
    }
}
