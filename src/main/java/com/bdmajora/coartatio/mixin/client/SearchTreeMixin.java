package com.bdmajora.coartatio.mixin.client;

import net.minecraft.client.util.SearchTree;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// Defers SearchTree's eager index build: vanilla builds two suffix arrays over every item stack
// at startup and on every resource reload, but most sessions never open the creative search box.
// recalculate() only marks the tree stale now; the first search() actually builds it.
@Mixin(SearchTree.class)
public abstract class SearchTreeMixin<T> {
    @Unique
    private boolean coartatio$stale = true;

    @Shadow
    public abstract void recalculate();

    // Swallows the eager build; flag-guarded rather than removed so a genuine rebuild
    // (resource reload, mod adding items) still invalidates correctly.
    @Inject(method = "recalculate", at = @At("HEAD"), cancellable = true)
    private void coartatio$deferRecalculate(CallbackInfo ci) {
        if (this.coartatio$stale) {
            ci.cancel();
        }
    }

    @Inject(method = "search", at = @At("HEAD"))
    private void coartatio$buildOnDemand(String query, CallbackInfoReturnable<List<T>> cir) {
        if (this.coartatio$stale) {
            // Clear first: the guard above is what makes the re-entrant call do real work.
            this.coartatio$stale = false;
            recalculate();
        }
    }

    // A rebuild request while already built goes through normally, then re-arms the deferral.
    @Inject(method = "add", at = @At("HEAD"))
    private void coartatio$invalidate(T item, CallbackInfo ci) {
        this.coartatio$stale = true;
    }
}
