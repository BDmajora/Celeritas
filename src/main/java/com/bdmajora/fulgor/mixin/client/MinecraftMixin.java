package com.bdmajora.fulgor.mixin.client;

import com.bdmajora.fulgor.FulgorConfig;
import com.bdmajora.fulgor.api.LightUpdateProcessor;
import com.bdmajora.fulgor.api.LightingEngineProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.profiler.Profiler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client's once-per-tick flush.
 *
 * <p>On the server every path that reads light goes through the engine, so deferral resolves itself.
 * The client has one that does not: Impetus' terrain renderer copies light arrays straight out of the
 * chunk sections when it prepares a build task, which would silently capture a stale batch. So the
 * client tick resolves everything up front, in two stages that have to happen in this order.
 *
 * <p>First the world's engine runs, which writes the new light values and calls
 * {@code World.notifyLightSet} for each — filling the renderer's queue. Then the renderer's queue is
 * drained into chunk rebuilds. Draining first would leave a tick of latency on every light change.
 */
@SideOnly(Side.CLIENT)
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow
    @Final
    public Profiler profiler;

    @Shadow
    public WorldClient world;

    @Shadow
    public RenderGlobal renderGlobal;

    @Shadow
    private boolean isGamePaused;

    /**
     * Stage one, injected just before the {@code levelRenderer} profiler section opens.
     *
     * <p>Opening a {@code lighting} section here and letting vanilla's own
     * {@code endStartSection("levelRenderer")} close it keeps the profiler balanced and puts the cost
     * where it can be seen.
     */
    @Inject(method = "runTick", at = @At(value = "CONSTANT", args = "stringValue=levelRenderer"))
    private void fulgor$processWorldLightUpdates(CallbackInfo ci) {
        if (this.world == null || (this.isGamePaused && FulgorConfig.get().skipUpdatesWhilePaused)) {
            return;
        }

        this.profiler.endStartSection("lighting");

        ((LightingEngineProvider) this.world).fulgor$getLightingEngine().processLightUpdates();
    }

    /**
     * Stage two, injected just before the {@code level} section, which is immediately after
     * {@code renderGlobal.updateClouds()} — the call whose light-update drain Fulgor took over.
     */
    @Inject(method = "runTick", at = @At(value = "CONSTANT", args = "stringValue=level"))
    private void fulgor$processRenderLightUpdates(CallbackInfo ci) {
        if (this.isGamePaused && FulgorConfig.get().skipUpdatesWhilePaused) {
            return;
        }

        // instanceof rather than a plain cast: the renderer-side queue is a separate config switch, and
        // with it off RenderGlobal keeps vanilla's own drain and does not implement this.
        if (this.renderGlobal instanceof LightUpdateProcessor) {
            ((LightUpdateProcessor) this.renderGlobal).fulgor$processLightUpdates();
        }
    }
}
