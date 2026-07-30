package com.bdmajora.impetus.mixin.core.shader;

import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bdmajora.impetus.iris.Iris;
import com.bdmajora.impetus.iris.pipeline.IrisRenderingPipeline;
import com.bdmajora.impetus.iris.shaderpack.ShaderPack;
import com.bdmajora.impetus.iris.shaderpack.loading.ProgramId;

/**
 * Switches the sky phase to {@code gbuffers_skytextured} for the textured celestial bodies (sun and moon) inside
 * {@code renderSky}, and back to {@code gbuffers_skybasic} once they are drawn — OptiFine's
 * {@code preCelestialRotate}/sun/moon program split. The outer {@code "sky"} profiler anchor in
 * {@code EntityRendererMixin} has already selected {@code skybasic} for the sky discs/horizon.
 */
@Mixin(RenderGlobal.class)
public class RenderGlobalMixin {
    private static final String SUN_TEXTURES_FIELD =
            "Lnet/minecraft/client/renderer/RenderGlobal;SUN_TEXTURES:Lnet/minecraft/util/ResourceLocation;";

    /**
     * Right before the vanilla sky disc VBO is drawn (skybasic phase already active), draw OptiFine's horizon fill so
     * the thin uncovered band at the horizon lands in colortex1 with the atmospheric sky colour instead of stale HDR.
     * Matches OptiFine's {@code Shaders.preSkyList()} call site (immediately before {@code skyVBO.bindBuffer()}).
     */
    @Inject(method = "renderSky(FI)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/vertex/VertexBuffer;bindBuffer()V", ordinal = 0))
    private void impetus$drawHorizon(float partialTicks, int pass, CallbackInfo ci) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.drawSkyHorizon();
        }
    }

    @Inject(method = "renderSky(FI)V",
            at = @At(value = "FIELD", target = SUN_TEXTURES_FIELD, opcode = org.objectweb.asm.Opcodes.GETSTATIC))
    private void impetus$beginSunMoon(float partialTicks, int pass, CallbackInfo ci) {
        impetus$setPhase(ProgramId.SkyTextured);
    }

    @Inject(method = "renderSky(FI)V",
            slice = @Slice(from = @At(value = "FIELD", target = SUN_TEXTURES_FIELD,
                    opcode = org.objectweb.asm.Opcodes.GETSTATIC)),
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;disableTexture2D()V", ordinal = 0))
    private void impetus$endSunMoon(float partialTicks, int pass, CallbackInfo ci) {
        impetus$setPhase(ProgramId.SkyBasic);
    }

    /**
     * OptiFine 1.12 brackets the actual cloud geometry inside {@code RenderGlobal.renderClouds} with
     * {@code Shaders.beginClouds()}/{@code Shaders.endClouds()}, while modern Iris brackets
     * {@code LevelRenderer.renderClouds} at method entry/return. Do this at the geometry boundary instead of at
     * {@code EntityRenderer}'s profiler label; shader-pack properties can cancel the dispatcher, and a leaked
     * {@code gbuffers_clouds} phase leaves colortex4 selected until the composite chain.
     */
    @Inject(method = "renderClouds(FIDDD)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;disableCull()V",
                    ordinal = 0),
            require = 0)
    private void impetus$beginFastClouds(float partialTicks, int pass, double x, double y, double z, CallbackInfo ci) {
        impetus$setPhase(ProgramId.Clouds);
    }

    @Inject(method = "renderClouds(FIDDD)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;enableCull()V",
                    ordinal = 0,
                    shift = At.Shift.AFTER),
            require = 0)
    private void impetus$endFastClouds(float partialTicks, int pass, double x, double y, double z, CallbackInfo ci) {
        impetus$setPhase(null);
    }

    @Inject(method = "renderCloudsFancy(FIDDD)V", at = @At("HEAD"), require = 0)
    private void impetus$beginFancyClouds(float partialTicks, int pass, double x, double y, double z, CallbackInfo ci) {
        if (!impetus$shaderPackForcesFastOrOffClouds()) {
            impetus$setPhase(ProgramId.Clouds);
        }
    }

    @Inject(method = "renderCloudsFancy(FIDDD)V", at = @At("RETURN"), require = 0)
    private void impetus$endFancyClouds(float partialTicks, int pass, double x, double y, double z, CallbackInfo ci) {
        if (!impetus$shaderPackForcesFastOrOffClouds()) {
            impetus$setPhase(null);
        }
    }

    private static boolean impetus$shaderPackForcesFastOrOffClouds() {
        ShaderPack pack = Iris.getCurrentPack();
        if (pack == null) {
            return false;
        }
        String mode = pack.getProperties().getCloudMode().orElse("");
        return "fast".equals(mode) || "off".equals(mode) || "none".equals(mode) || "false".equals(mode);
    }

    private static void impetus$setPhase(ProgramId phase) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.setPhase(phase);
        }
    }
}
