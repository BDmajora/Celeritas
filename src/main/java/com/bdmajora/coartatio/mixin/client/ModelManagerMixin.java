package com.bdmajora.coartatio.mixin.client;

import com.bdmajora.coartatio.Coartatio;
import net.minecraft.client.renderer.block.model.ModelManager;
import net.minecraft.client.resources.IResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the lifecycle of the bake-scoped pools.
 *
 * <p>{@code ModelManager.onResourceManagerReload} brackets the entire bake — it constructs the
 * bakery, runs it, and installs the result — so it is the one place that sees both ends of the phase.
 * Forge replaces the {@code ModelBakery} inside with its own {@code ModelLoader} but leaves this
 * method's signature alone, so the hook is stable across Forge versions and unaffected by mods that
 * add model loaders.
 *
 * <p>Opening at {@code HEAD} rather than clearing at {@code RETURN} means a second reload (resource
 * pack change, F3+T) does not keep the previous pack's geometry alive through the pool.
 */
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
