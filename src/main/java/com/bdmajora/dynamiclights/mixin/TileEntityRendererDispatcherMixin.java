package com.bdmajora.dynamiclights.mixin;

import com.bdmajora.dynamiclights.DynamicLights;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

// Lights block entity renderers by the dynamic light at their position, since chests, banners and signs are drawn outside the chunk mesh; targets render(TileEntity, float, int) specifically to avoid Impetus' and Extras' injectors on the other overloads
@Mixin(TileEntityRendererDispatcher.class)
public abstract class TileEntityRendererDispatcherMixin {
    @ModifyArgs(
            method = "render(Lnet/minecraft/tileentity/TileEntity;FI)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getCombinedLight(Lnet/minecraft/util/math/BlockPos;I)I"))
    private void impetus$dynamicBlockEntityLight(Args args) {
        if (!DynamicLights.options().mode.isEnabled()) {
            return;
        }

        BlockPos pos = args.get(0);
        int vanilla = args.get(1);

        args.set(1, Math.max(vanilla, (int) DynamicLights.engine().getDynamicLightLevel(pos)));
    }
}
