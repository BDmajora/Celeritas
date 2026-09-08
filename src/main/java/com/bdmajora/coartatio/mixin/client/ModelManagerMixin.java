package com.bdmajora.coartatio.mixin.client;

import com.bdmajora.coartatio.Coartatio;
import net.minecraft.client.renderer.block.model.ModelManager;
import net.minecraft.client.resources.IResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Patches ModelManager.onResourceManagerReload, which brackets the whole model bake (builds the
// bakery, runs it, installs the result). Opens/closes the bake-scoped pools around it so a
// second reload (resource pack swap, F3+T) doesn't keep the previous pack's geometry alive.
@Mixin(ModelManager.class)
public class ModelManagerMixin {
    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void coartatio$openPools(IResourceManager resourceManager, CallbackInfo ci) {
        Coartatio.onResourceReloadStart();
    }

    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void coartatio$closePools(IResourceManager resourceManager, CallbackInfo ci) {
        Coartatio.onResourceReloadFinish();
    }
}
