package com.bdmajora.extras.mixin.render.block_entity;

import com.bdmajora.extras.Extras;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.tileentity.TileEntityBeaconRenderer;
import net.minecraft.tileentity.TileEntityBeacon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Beacon beam visibility and height.
 *
 * <p>The height clamp is applied where the segments are drawn rather than where they are computed:
 * 1.12.2's renderer has no local "beam height" to modify — {@code render} hands {@code renderBeacon}
 * a list of segments — so wrapping the per-segment draw is the only place that sees both the value
 * actually consumed and how much height the earlier segments already used.
 *
 * <p>The beacon has to be remembered across that call because {@code renderBeacon} does not receive
 * it, and the world height is needed to know where the ceiling is.
 */
@Mixin(TileEntityBeaconRenderer.class)
public class TileEntityBeaconRendererMixin {
    @Unique
    private TileEntityBeacon impetus$currentBeacon;

    @Inject(
            method = "render(Lnet/minecraft/tileentity/TileEntityBeacon;DDDFIF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void impetus$beginRender(TileEntityBeacon beacon, double x, double y, double z,
                                     float partialTicks, int destroyStage, float alpha, CallbackInfo ci) {
        this.impetus$currentBeacon = null;

        if (!Extras.options().render.beacons) {
            ci.cancel();
            return;
        }

        this.impetus$currentBeacon = beacon;
    }

    @Inject(
            method = "render(Lnet/minecraft/tileentity/TileEntityBeacon;DDDFIF)V",
            at = @At("RETURN")
    )
    private void impetus$endRender(TileEntityBeacon beacon, double x, double y, double z,
                                   float partialTicks, int destroyStage, float alpha, CallbackInfo ci) {
        this.impetus$currentBeacon = null;
    }

    @WrapOperation(
            method = "renderBeacon(DDDDDLjava/util/List;D)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/tileentity/TileEntityBeaconRenderer;renderBeamSegment(DDDDDDII[F)V")
    )
    private void impetus$limitBeamSegment(double x, double y, double z, double partialTicks,
                                          double beamScale, double worldTime,
                                          int segmentStart, int segmentHeight, float[] colors,
                                          Operation<Void> original) {
        TileEntityBeacon beacon = this.impetus$currentBeacon;

        if (Extras.options().render.limitBeaconBeamHeight && beacon != null && beacon.getWorld() != null) {
            int remaining = beacon.getWorld().getHeight() - beacon.getPos().getY() - segmentStart;
            segmentHeight = Math.min(segmentHeight, Math.max(0, remaining));
        }

        if (segmentHeight > 0) {
            original.call(x, y, z, partialTicks, beamScale, worldTime, segmentStart, segmentHeight, colors);
        }
    }
}
