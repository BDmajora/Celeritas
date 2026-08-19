package com.bdmajora.impetus.mixin.core.shader;

import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bdmajora.impetus.iris.material.WorldRenderingSettings;
import com.bdmajora.impetus.iris.uniforms.CapturedRenderingState;

import java.util.ArrayDeque;
import java.util.Deque;

@Mixin(TileEntityRendererDispatcher.class)
public class TileEntityRendererDispatcherIdMixin {
    @Unique
    private final Deque<Integer> impetus$blockEntityIdStack = new ArrayDeque<>();

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntity;FI)V", at = @At("HEAD"))
    private void impetus$beginBlockEntity(TileEntity tileEntity, float partialTicks, int destroyStage, CallbackInfo ci) {
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        this.impetus$blockEntityIdStack.push(state.getCurrentRenderedBlockEntity());
        state.setCurrentRenderedBlockEntity(WorldRenderingSettings.getBlockEntityId(tileEntity));
    }

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntity;FI)V", at = @At("RETURN"))
    private void impetus$endBlockEntity(TileEntity tileEntity, float partialTicks, int destroyStage, CallbackInfo ci) {
        CapturedRenderingState.INSTANCE.setCurrentRenderedBlockEntity(
                this.impetus$blockEntityIdStack.isEmpty() ? -1 : this.impetus$blockEntityIdStack.pop());
    }
}
