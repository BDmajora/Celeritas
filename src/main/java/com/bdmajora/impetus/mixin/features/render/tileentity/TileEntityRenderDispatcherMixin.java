package com.bdmajora.impetus.mixin.features.render.tileentity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityPiston;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TileEntityRendererDispatcher.class)
public class TileEntityRenderDispatcherMixin {
    // lets some invalid tile entities still be rendered
    // modern vanilla versions do this for all of them, but we cannot here: mods rely on their TESR
    // not being invoked once the tile entity is invalid
    // @author / @reason are Mixin's required metadata on an overwrite-class injector
    @WrapOperation(method = "getRenderer(Lnet/minecraft/tileentity/TileEntity;)Lnet/minecraft/client/renderer/tileentity/TileEntitySpecialRenderer;", at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/TileEntity;isInvalid()Z"))
    private boolean allowSomeInvalidTESRs(TileEntity te, Operation<Boolean> original) {
        if (te instanceof TileEntityPiston) {
            // Pistons invalidate their TE before the chunk renderer has finished the chunk update
            return false;
        }
        return original.call(te);
    }
}
