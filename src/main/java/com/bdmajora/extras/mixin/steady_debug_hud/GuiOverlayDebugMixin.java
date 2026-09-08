package com.bdmajora.extras.mixin.steady_debug_hud;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import net.minecraft.client.gui.GuiOverlayDebug;
import net.minecraft.client.gui.ScaledResolution;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Throttles how often the F3 overlay text is rebuilt.
 *
 * <p>Vanilla recomputes every line each frame, and some of those lines are not cheap — the biome
 * lookup and the chunk-relative position both hit the world. Rebuilding once a tick instead makes
 * the numbers readable as well as cheaper: at 200 FPS the vanilla overlay changes too fast to read.
 *
 * <p>The two cached lists are keyed off a single decision made at the top of the frame, so the left
 * and right columns can never show text from different ticks.
 */
@Mixin(GuiOverlayDebug.class)
public abstract class GuiOverlayDebugMixin {
    @Unique
    private final java.util.List<String> impetus$leftCache = new java.util.ArrayList<>();
    @Unique
    private final java.util.List<String> impetus$rightCache = new java.util.ArrayList<>();
    @Unique
    private long impetus$nextUpdateNanos;
    @Unique
    private boolean impetus$rebuild = true;

    @Inject(method = "renderDebugInfo", at = @At("HEAD"))
    private void impetus$decideRebuild(ScaledResolution resolution, CallbackInfo ci) {
        ExtrasConfig.ExtraSettings settings = Extras.options().extra;

        if (!settings.steadyDebugHud) {
            this.impetus$rebuild = true;
            return;
        }

        long now = System.nanoTime();
        if (now >= this.impetus$nextUpdateNanos) {
            this.impetus$rebuild = true;
            this.impetus$nextUpdateNanos = now + settings.steadyDebugHudRefreshInterval * 50_000_000L;
        } else {
            this.impetus$rebuild = false;
        }
    }

    /**
     * A copy, not the cache itself.
     *
     * <p>{@code renderDebugInfoLeft} appends three lines ("Debug: Pie [shift]...", the F3+Q hint and
     * a blank) to whatever {@code call()} hands it. Returning the live cache would let it grow by
     * three lines on every frame until the next rebuild, so the overlay would sprout duplicate hint
     * lines between refreshes. One list copy per frame is still far cheaper than recomputing the
     * text, which is the whole point of this mixin.
     */
    @Inject(method = "call()Ljava/util/List;", at = @At("HEAD"), cancellable = true)
    private void impetus$leftFromCache(CallbackInfoReturnable<java.util.List<String>> cir) {
        if (!this.impetus$rebuild) {
            cir.setReturnValue(new java.util.ArrayList<>(this.impetus$leftCache));
        }
    }

    @Inject(method = "call()Ljava/util/List;", at = @At("RETURN"))
    private void impetus$cacheLeft(CallbackInfoReturnable<java.util.List<String>> cir) {
        if (this.impetus$rebuild) {
            this.impetus$leftCache.clear();
            this.impetus$leftCache.addAll(cir.getReturnValue());
        }
    }

    /** Copied for the same reason as the left column, and because a mod may append to this one too. */
    @Inject(method = "getDebugInfoRight()Ljava/util/List;", at = @At("HEAD"), cancellable = true)
    private void impetus$rightFromCache(CallbackInfoReturnable<java.util.List<String>> cir) {
        if (!this.impetus$rebuild) {
            cir.setReturnValue(new java.util.ArrayList<>(this.impetus$rightCache));
        }
    }

    @Inject(method = "getDebugInfoRight()Ljava/util/List;", at = @At("RETURN"))
    private void impetus$cacheRight(CallbackInfoReturnable<java.util.List<String>> cir) {
        if (this.impetus$rebuild) {
            this.impetus$rightCache.clear();
            this.impetus$rightCache.addAll(cir.getReturnValue());
        }
    }
}
