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

// folds dynamic light into the terrain lightmap - this is where dynamic lights actually reach the world
// Impetus' chunk builder asks each block state for its packed lightmap coordinate (LightDataCache#compute),
// caches the answer for the section being compiled, and the smooth and flat light pipelines
// interpolate from there, so raising the value here is what makes a held torch light the floor
// runs on chunk-builder worker threads, which is why the engine's source set is behind a read/write
// lock and why the empty-set case short-circuits before taking it
// the opaque-cube test mirrors what the light pipelines expect: brightening the interior of a solid
// block does nothing useful and makes ambient occlusion disagree with the light it is shading
// light-emitting blocks are exempt because they are read for their own glow
@SideOnly(Side.CLIENT)
@Mixin(BlockStateContainer.StateImplementation.class)
public abstract class StateImplementationMixin {
    @Inject(method = "getPackedLightmapCoords", at = @At("RETURN"), cancellable = true)
    private void impetus$addDynamicLight(IBlockAccess source, BlockPos pos,
                                         CallbackInfoReturnable<Integer> cir) {
        if (!DynamicLights.options().mode.isEnabled()) {
            return;
        }

        // LightDataCache probes every block state against an empty world to decide whether it is
        // emissive. That probe is not a position in the world, so there is no dynamic light to add
        // and iterating the light sources for it would be pure waste on the chunk-build hot path.
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
