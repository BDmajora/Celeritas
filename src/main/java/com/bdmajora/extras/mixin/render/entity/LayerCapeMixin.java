package com.bdmajora.extras.mixin.render.entity;

import com.bdmajora.extras.Extras;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.layers.LayerCape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// OptiFine's Show Capes switch; elytra shares the texture slot but is a different layer and hiding wings would be a gameplay change
@Mixin(LayerCape.class)
public class LayerCapeMixin {
    @Inject(
            method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void impetus$doRenderLayer(AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                                       float partialTicks, float ageInTicks, float netHeadYaw,
                                       float headPitch, float scale, CallbackInfo ci) {
        if (!Extras.options().detail.showCapes) {
            ci.cancel();
        }
    }
}
