package org.taumc.celeritas.mixin.core.shader;

import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.taumc.celeritas.iris.Iris;
import org.taumc.celeritas.iris.pipeline.IrisRenderingPipeline;
import org.taumc.celeritas.iris.shaderpack.loading.ProgramId;

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

    @Inject(method = "renderSky(FI)V",
            at = @At(value = "FIELD", target = SUN_TEXTURES_FIELD, opcode = org.objectweb.asm.Opcodes.GETSTATIC))
    private void celeritas$beginSunMoon(float partialTicks, int pass, CallbackInfo ci) {
        celeritas$setPhase(ProgramId.SkyTextured);
    }

    @Inject(method = "renderSky(FI)V",
            slice = @Slice(from = @At(value = "FIELD", target = SUN_TEXTURES_FIELD,
                    opcode = org.objectweb.asm.Opcodes.GETSTATIC)),
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;disableTexture2D()V", ordinal = 0))
    private void celeritas$endSunMoon(float partialTicks, int pass, CallbackInfo ci) {
        celeritas$setPhase(ProgramId.SkyBasic);
    }

    private static void celeritas$setPhase(ProgramId phase) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.setPhase(phase);
        }
    }
}
