package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.iris.Iris;
import com.bdmajora.impetus.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntityBeaconRenderer;
import net.minecraft.tileentity.TileEntityBeacon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The {@code beacon.beam.depth} shaders.properties toggle. Vanilla draws the beacon beam without depth writes, so it
 * never appears in {@code depthtex}. A pack that reconstructs world position from depth (for fog, volumetrics or
 * reflections) needs the beam there, and Complementary asks for it.
 */
@Mixin(TileEntityBeaconRenderer.class)
public class BeaconBeamDepthMixin {
    /** GL_DEPTH_WRITEMASK — captured so the restore puts back whatever vanilla had, rather than assuming. */
    private static final int GL_DEPTH_WRITEMASK = 0x0B72;

    @Unique
    private boolean impetus$savedDepthMask;
    @Unique
    private boolean impetus$depthMaskOverridden;

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntityBeacon;DDDFIF)V",
            at = @At("HEAD"), require = 0)
    private void impetus$beaconBeamDepthOn(TileEntityBeacon beacon, double x, double y, double z,
                                           float partialTicks, int destroyStage, float alpha, CallbackInfo ci) {
        if (impetus$wantsBeamDepth()) {
            this.impetus$savedDepthMask = LWJGL.glGetBoolean(GL_DEPTH_WRITEMASK);
            this.impetus$depthMaskOverridden = true;
            GlStateManager.depthMask(true);
        }
    }

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntityBeacon;DDDFIF)V",
            at = @At("RETURN"), require = 0)
    private void impetus$beaconBeamDepthOff(TileEntityBeacon beacon, double x, double y, double z,
                                            float partialTicks, int destroyStage, float alpha, CallbackInfo ci) {
        if (this.impetus$depthMaskOverridden) {
            this.impetus$depthMaskOverridden = false;
            GlStateManager.depthMask(this.impetus$savedDepthMask);
        }
    }

    private static boolean impetus$wantsBeamDepth() {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        return pipeline != null && pipeline.shouldWriteBeaconBeamToDepthBuffer();
    }
}
