package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.umbra.Umbra;
import com.bdmajora.impetus.umbra.pipeline.UmbraRenderingPipeline;
import com.bdmajora.impetus.umbra.pipeline.UmbraShadowRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntityBeaconRenderer;
import net.minecraft.tileentity.TileEntityBeacon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// The "beacon.beam.depth" shaders.properties toggle; vanilla draws the beam without depth writes, and a pack reconstructing world position from depth (Complementary) needs it in depthtex
@Mixin(TileEntityBeaconRenderer.class)
public class BeaconBeamDepthMixin {
    // GL_DEPTH_WRITEMASK — captured so the restore puts back whatever vanilla had, rather than assuming.
    private static final int GL_DEPTH_WRITEMASK = 0x0B72;

    @Unique
    private boolean impetus$savedDepthMask;
    @Unique
    private boolean impetus$depthMaskOverridden;

    // Umbra parity (MixinBeaconRenderer#umbra$noLightBeamInShadowPass): the beam is a tall unlit column that would cast a full-height shadow pillar, so it is cancelled at HEAD in the shadow pass, which also skips the depth-mask override below
    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntityBeacon;DDDFIF)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void impetus$noBeamInShadowPass(TileEntityBeacon beacon, double x, double y, double z,
                                            float partialTicks, int destroyStage, float alpha, CallbackInfo ci) {
        if (Umbra.getRenderingPipeline() != null && UmbraShadowRenderer.isShadowPass()) {
            ci.cancel();
        }
    }

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
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        return pipeline != null && pipeline.shouldWriteBeaconBeamToDepthBuffer();
    }
}
