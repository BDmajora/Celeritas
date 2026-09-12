package com.bdmajora.coarctatio.mixin.client;

import com.bdmajora.coarctatio.Coarctatio;
import net.minecraft.client.renderer.block.model.ModelManager;
import net.minecraft.client.resources.IResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// onResourceManagerReload brackets the whole model bake, so the bake-scoped pools open and close around it; otherwise a second reload keeps the previous pack's geometry alive
@Mixin(ModelManager.class)
public class ModelManagerMixin {
    // Bake is about to start; arm the pools
    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void coarctatio$openPools(IResourceManager resourceManager, CallbackInfo ci) {
        Coarctatio.onResourceReloadStart();
    }

    // Bake finished; release everything scoped to it
    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void coarctatio$closePools(IResourceManager resourceManager, CallbackInfo ci) {
        Coarctatio.onResourceReloadFinish();
    }
}
