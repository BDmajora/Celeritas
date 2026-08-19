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

/**
 * Defers building the creative-search index until something actually searches.
 *
 * <p>ModernFix's {@code blast_search_trees}/{@code lazy_search_tree_registry}, and it subsumes
 * FoamFix's and LoliASM's JEI-specific variants without needing JEI on the classpath.
 *
 * <p>{@code Minecraft.populateSearchTreeManager} builds two suffix arrays over every item stack in
 * the game — names and registry IDs — at startup and again on every resource reload. A suffix array
 * over tens of thousands of stacks is not cheap in memory or in time, and the overwhelming majority
 * of sessions never type in the creative search box at all. With JEI installed it is never consulted
 * even once, because JEI replaces that GUI and maintains its own index.
 *
 * <p>So: {@code recalculate} only marks the tree stale, and the first {@code search} builds it. The
 * index is identical either way — this changes when the work happens, not what it produces.
 */
@Mixin(SearchTree.class)
public abstract class SearchTreeMixin<T> {
    @Unique
    private boolean coartatio$stale = true;

    @Shadow
    public abstract void recalculate();

    /**
     * Swallows the eager build. Guarded by a flag rather than removed outright so that a genuine
     * rebuild — a resource reload, or a mod adding items — still invalidates correctly.
     */
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

    /** A rebuild request while already built goes through normally, then re-arms the deferral. */
    @Inject(method = "add", at = @At("HEAD"))
    private void coartatio$invalidate(T item, CallbackInfo ci) {
        this.coartatio$stale = true;
    }
}
