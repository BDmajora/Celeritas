package com.bdmajora.dynamiclights.mixin;

import com.bdmajora.dynamiclights.DynamicLights;
import com.bdmajora.dynamiclights.client.DynamicLightsEngine;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.GuiOverlayDebug;
import net.minecraft.util.text.TextFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Adds a tracked-source count to the F3 overlay.
 *
 * <p>Hooks the {@code call()} <em>call site</em> in {@code renderDebugInfoLeft} rather than injecting
 * at {@code call()}'s own RETURN. Extras' {@code steady_debug_hud} mixin caches that method's result
 * and serves a copy from a cancellable HEAD injection, which adds a second RETURN instruction to the
 * method — so a RETURN injector here would fire on both the real return and the cache-hit return,
 * and which of those happens depends on mixin application order. The call site exists exactly once
 * per frame regardless, so this stays correct whichever way the two mixins are ordered.
 */
@Mixin(GuiOverlayDebug.class)
public abstract class GuiOverlayDebugMixin {
    @ModifyExpressionValue(
            method = "renderDebugInfoLeft",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiOverlayDebug;call()Ljava/util/List;"))
    private List<String> impetus$addDynamicLightInfo(List<String> lines) {
        if (!DynamicLights.options().showDebugInfo) {
            return lines;
        }

        DynamicLightsEngine engine = DynamicLights.engine();
        StringBuilder line = new StringBuilder("Dynamic Light Sources: ")
                .append(engine.getLightSourcesCount())
                .append(" (U: ")
                .append(engine.getLastUpdateCount());

        if (!DynamicLights.options().mode.isEnabled()) {
            line.append("; ").append(TextFormatting.RED).append("Disabled").append(TextFormatting.RESET);
        }

        lines.add(line.append(')').toString());
        return lines;
    }
}
