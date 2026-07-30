package com.bdmajora.impetus.mixin.core.shader;

import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bdmajora.impetus.iris.material.WorldRenderingSettings;
import com.bdmajora.impetus.iris.uniforms.CapturedRenderingState;

import java.util.ArrayDeque;
import java.util.Deque;

@Mixin(RenderItem.class)
public class RenderItemItemIdMixin {
    @Unique
    private final Deque<Integer> impetus$itemIdStack = new ArrayDeque<>();

    @Inject(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/IBakedModel;)V",
            at = @At("HEAD"))
    private void impetus$beginItem(ItemStack stack, IBakedModel model, CallbackInfo ci) {
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        this.impetus$itemIdStack.push(state.getCurrentRenderedItem());
        state.setCurrentRenderedItem(WorldRenderingSettings.getItemId(stack));
    }

    @Inject(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/IBakedModel;)V",
            at = @At("RETURN"))
    private void impetus$endItem(ItemStack stack, IBakedModel model, CallbackInfo ci) {
        CapturedRenderingState.INSTANCE.setCurrentRenderedItem(
                this.impetus$itemIdStack.isEmpty() ? -1 : this.impetus$itemIdStack.pop());
    }
}
