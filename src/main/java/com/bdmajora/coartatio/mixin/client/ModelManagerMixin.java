package com.bdmajora.coartatio.mixin.client;

import com.bdmajora.coartatio.Coartatio;
import net.minecraft.client.renderer.block.model.ModelManager;
import net.minecraft.client.resources.IResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// onResourceManagerReload brackets the whole model bake, so the bake-scoped pools open and close around it
// Without this a second reload (pack swap, F3+T) keeps the previous pack's geometry alive
@Mixin(ModelManager.class)
public class ModelManagerMixin {
    // Bake is about to start; arm the pools
    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void coartatio$openPools(IResourceManager resourceManager, CallbackInfo ci) {
        Coartatio.onResourceReloadStart();
    }

    // Bake finished; release everything scoped to it
    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void coartatio$closePools(IResourceManager resourceManager, CallbackInfo ci) {
        Coartatio.onResourceReloadFinish();
    }
}
