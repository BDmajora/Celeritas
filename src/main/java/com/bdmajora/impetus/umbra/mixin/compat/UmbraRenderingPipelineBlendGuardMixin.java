package com.bdmajora.impetus.umbra.mixin.compat;

import com.bdmajora.impetus.umbra.gl.blending.BlendOverrideGuard;
import com.bdmajora.impetus.umbra.gl.blending.ProgramAlphaTest;
import com.bdmajora.impetus.umbra.gl.blending.ProgramBlendState;
import com.bdmajora.impetus.umbra.pipeline.UmbraRenderingPipeline;
import com.bdmajora.impetus.umbra.shaderpack.loading.ProgramId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(UmbraRenderingPipeline.class)
public abstract class UmbraRenderingPipelineBlendGuardMixin {
    @Inject(method = "beginWorldRendering(F)V", at = @At("HEAD"))
    private void impetus$releaseAtWorldStart(float partialTicks, CallbackInfo ci) {
        BlendOverrideGuard.release();
    }

    @Inject(method = "setPhase(Lcom/bdmajora/impetus/umbra/shaderpack/loading/ProgramId;)V", at = @At("HEAD"))
    private void impetus$releaseAtPhaseChange(ProgramId phase, CallbackInfo ci) {
        BlendOverrideGuard.release();
    }

    @Inject(method = "onTerrainDraw([ILcom/bdmajora/impetus/umbra/gl/blending/ProgramBlendState;)V", at = @At("HEAD"))
    private void impetus$releaseAtTerrainDraw(int[] drawBuffers, ProgramBlendState blendState, CallbackInfo ci) {
        BlendOverrideGuard.release();
    }

    @Inject(
            method = "onTerrainDraw([ILcom/bdmajora/impetus/umbra/gl/blending/ProgramBlendState;Lcom/bdmajora/impetus/umbra/gl/blending/ProgramAlphaTest;Z)V",
            at = @At("HEAD"))
    private void impetus$releaseAtTerrainDrawFull(
            int[] drawBuffers, ProgramBlendState blendState, ProgramAlphaTest alphaTest,
            boolean translucentPass, CallbackInfo ci) {
        BlendOverrideGuard.release();
    }

    @Inject(method = "beginTranslucents()V", at = @At("HEAD"))
    private void impetus$releaseAtTranslucents(CallbackInfo ci) {
        BlendOverrideGuard.release();
    }

    @Inject(method = "beginHand()V", at = @At("HEAD"))
    private void impetus$releaseAtHandDepthCopy(CallbackInfo ci) {
        BlendOverrideGuard.release();
    }

    @Inject(method = "beginHandRendering()Z", at = @At("HEAD"))
    private void impetus$releaseAtHandRendering(CallbackInfoReturnable<Boolean> cir) {
        BlendOverrideGuard.release();
    }

    @Inject(method = "beginHandTranslucentRendering()Z", at = @At("HEAD"))
    private void impetus$releaseAtHandTranslucentRendering(CallbackInfoReturnable<Boolean> cir) {
        BlendOverrideGuard.release();
    }

    @Inject(method = "endHandRendering()V", at = @At("HEAD"))
    private void impetus$releaseAtHandEnd(CallbackInfo ci) {
        BlendOverrideGuard.release();
    }

    @Inject(method = "finishWorldRendering()V", at = @At("HEAD"))
    private void impetus$releaseAtWorldFinish(CallbackInfo ci) {
        BlendOverrideGuard.release();
    }
}
