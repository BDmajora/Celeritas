package com.bdmajora.coarctatio.mixin.client;

import net.minecraft.client.util.SearchTree;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// Defers SearchTree's eager index build (two suffix arrays over every item stack at startup and each reload) since most sessions never open creative search; recalculate() marks stale, the first search() builds
@Mixin(SearchTree.class)
public abstract class SearchTreeMixin<T> {
    @Unique
    private boolean coarctatio$stale = true;

    @Shadow
    public abstract void recalculate();

    // Swallows the eager build; flag-guarded rather than removed so a genuine rebuild (resource reload, mod adding items) still invalidates
    @Inject(method = "recalculate", at = @At("HEAD"), cancellable = true)
    private void coarctatio$deferRecalculate(CallbackInfo ci) {
        if (this.coarctatio$stale) {
            ci.cancel();
        }
    }

    @Inject(method = "search", at = @At("HEAD"))
    private void coarctatio$buildOnDemand(String query, CallbackInfoReturnable<List<T>> cir) {
        if (this.coarctatio$stale) {
            // Clear first: the guard above is what makes the re-entrant call do real work.
            this.coarctatio$stale = false;
            recalculate();
        }
    }

    // A rebuild request while already built goes through normally, then re-arms the deferral.
    @Inject(method = "add", at = @At("HEAD"))
    private void coarctatio$invalidate(T item, CallbackInfo ci) {
        this.coarctatio$stale = true;
    }
}
