package org.taumc.celeritas.mixin.core.shader;

import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.taumc.celeritas.iris.Iris;
import org.taumc.celeritas.iris.pipeline.IrisRenderingPipeline;
import org.taumc.celeritas.iris.shaderpack.loading.ProgramId;
import org.taumc.celeritas.iris.uniforms.CapturedRenderingState;

/**
 * Drives the Iris frame pipeline from vanilla's world render, the way modern Iris hooks {@code LevelRenderer}:
 * <ul>
 * <li>{@code renderWorld} HEAD — build the pipeline if a pack was (re)loaded, then bind the gbuffer so the whole world
 * pass (vanilla's fog-colored clear included) renders into the shader render targets;</li>
 * <li>inside {@code renderWorldPass}, right after {@code setupCameraTransform}/{@code ActiveRenderInfo.updateRenderInfo}
 * (located via the {@code "frustum"} profiler ldc, a pure-vanilla anchor) — capture the frame's camera matrices and fog
 * color for the uniform providers;</li>
 * <li>{@code renderWorld} RETURN — run the composite/final chain and hand the finished image to Minecraft's
 * framebuffer.</li>
 * </ul>
 * Every hook is a no-op when no shader pack is active.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
    private static final String PROFILER_END_START =
            "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V";

    @Shadow
    private float fogColorRed;
    @Shadow
    private float fogColorGreen;
    @Shadow
    private float fogColorBlue;

    private static void celeritas$setPhase(ProgramId phase) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.setPhase(phase);
        }
    }

    @Inject(method = "renderWorld", at = @At("HEAD"))
    private void celeritas$beginShaderFrame(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        IrisRenderingPipeline pipeline = Iris.beginFrame();
        if (pipeline != null) {
            pipeline.beginWorldRendering(partialTicks);
        }
    }

    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING",
                    target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V",
                    args = "ldc=frustum"))
    private void celeritas$captureRenderingState(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            CapturedRenderingState.INSTANCE.setFogColor(this.fogColorRed, this.fogColorGreen, this.fogColorBlue);
            pipeline.captureRenderingState();
            // Shadow map renders here: matrices are fresh, and nothing has drawn into the gbuffer yet this frame.
            pipeline.renderShadowMap();
        }
    }

    // --- Fixed-function gbuffer phases, anchored on vanilla's profiler sections -----------------------------------

    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=sky"))
    private void celeritas$phaseSky(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        celeritas$setPhase(ProgramId.SkyBasic);
    }

    /**
     * Terrain draws next: reset to the plain fixed-function mask (and program 0) so that if the Embeddium terrain
     * override is unavailable, the default terrain shader writes only colortex0 instead of smearing through the last
     * sky program's DRAWBUFFERS. When the override works, its ChunkShaderInterface immediately sets the pack's mask.
     */
    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=terrain"))
    private void celeritas$phaseTerrain(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        celeritas$setPhase(null);
    }

    /** Matches both "entities" sections (the main one and the post-translucent leftovers). */
    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=entities"))
    private void celeritas$phaseEntities(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        celeritas$setPhase(ProgramId.Entities);
    }

    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=destroyProgress"))
    private void celeritas$phaseBlockDamage(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        celeritas$setPhase(ProgramId.DamagedBlock);
    }

    @Inject(method = "renderWorldPass", at = {
            @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=litParticles"),
            @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=particles")})
    private void celeritas$phaseParticles(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        celeritas$setPhase(ProgramId.TexturedLit);
    }

    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=weather"))
    private void celeritas$phaseWeather(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        celeritas$setPhase(ProgramId.Weather);
    }

    @Inject(method = "renderWorldPass", at = {
            @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=aboveClouds"),
            @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=clouds")})
    private void celeritas$phaseClouds(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        celeritas$setPhase(ProgramId.Clouds);
    }

    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=forge_render_last"))
    private void celeritas$phaseRenderLast(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        celeritas$setPhase(null);
    }

    // --- Mid-frame pipeline stages ---------------------------------------------------------------------------------

    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=translucent"))
    private void celeritas$beginTranslucents(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.beginTranslucents();
        }
    }

    /**
     * The composite/final chain runs HERE — at the {@code "hand"} anchor, BEFORE vanilla's {@code clear(256)} wipes
     * the depth buffer for the first-person hand. OptiFine does exactly this ({@code Shaders.renderCompositeFinal()}
     * before the clear): the composites need the scene depth (fog, sky tests, god rays), and the hand is then drawn
     * on top of the composited image in Minecraft's framebuffer.
     */
    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=hand"))
    private void celeritas$compositeBeforeHand(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.beginHand();
            pipeline.finishWorldRendering();
        }
    }

    @Inject(method = "renderWorld", at = @At("RETURN"))
    private void celeritas$finishShaderFrame(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.finishWorldRendering();
        }
    }
}
