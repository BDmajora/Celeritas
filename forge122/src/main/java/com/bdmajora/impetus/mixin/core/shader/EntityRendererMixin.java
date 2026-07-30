package com.bdmajora.impetus.mixin.core.shader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.EntityLivingBase;
import org.lwjgl.util.glu.Project;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraftforge.client.ForgeHooksClient;
import com.bdmajora.impetus.iris.Iris;
import com.bdmajora.impetus.iris.pipeline.IrisRenderingPipeline;
import com.bdmajora.impetus.iris.shaderpack.loading.ProgramId;
import com.bdmajora.impetus.iris.uniforms.CapturedRenderingState;

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

    /**
     * OptiFine's {@code configHandDepthMul} (Shaders.java default {@code 0.125}). Applied to the projection matrix as
     * {@code glScale(1, 1, HAND_DEPTH_MUL)} before {@code gluPerspective}, exactly like OptiFine's
     * {@code Shaders.applyHandDepth()}. Vanilla-without-shaders clears the depth buffer <em>before</em> drawing the
     * first-person hand, so the hand can never clip; the shader path instead draws the hand into the gbuffer (with the
     * world's depth already present) <em>before</em> the composite chain and depth clear, so it must compress the
     * hand's clip-space depth into a sliver at the near plane. Without this the hand depth-tests against real terrain —
     * clipping into close geometry and, worse, getting shaded by the composite pass at that bogus (deep, shadowed,
     * fogged) depth, which is why the held item renders as a flat dark blob instead of reacting to the pack lighting.
     */
    private static final float HAND_DEPTH_MUL = 0.125f;

    @Shadow
    private float fogColorRed;
    @Shadow
    private float fogColorGreen;
    @Shadow
    private float fogColorBlue;
    @Shadow
    @Final
    private Minecraft mc;
    @Shadow
    private ItemRenderer itemRenderer;
    @Shadow
    private boolean debugView;
    @Shadow
    private float farPlaneDistance;
    @Shadow
    private boolean renderHand;

    @Shadow
    private void renderHand(float partialTicks, int pass) {
    }

    @Shadow
    private float getFOVModifier(float partialTicks, boolean useFOVSetting) {
        return 0.0f;
    }

    @Shadow
    private void hurtCameraEffect(float partialTicks) {
    }

    @Shadow
    private void applyBobbing(float partialTicks) {
    }

    @Shadow
    public void enableLightmap() {
    }

    @Shadow
    public void disableLightmap() {
    }

    private static void impetus$setPhase(ProgramId phase) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.setPhase(phase);
        }
    }

    @Inject(method = "renderWorld", at = @At("HEAD"))
    private void impetus$beginShaderFrame(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        IrisRenderingPipeline pipeline = Iris.beginFrame();
        if (pipeline != null) {
            pipeline.beginWorldRendering(partialTicks);
        }
    }

    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING",
                    target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V",
                    args = "ldc=frustum"))
    private void impetus$captureRenderingState(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
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
    private void impetus$phaseSky(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        impetus$setPhase(ProgramId.SkyBasic);
    }

    /**
     * Terrain draws next: reset to the plain fixed-function mask (and program 0) so that if the Impetus terrain
     * override is unavailable, the default terrain shader writes only colortex0 instead of smearing through the last
     * sky program's DRAWBUFFERS. When the override works, its ChunkShaderInterface immediately sets the pack's mask.
     */
    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=terrain"))
    private void impetus$phaseTerrain(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        impetus$setPhase(null);
    }

    /** Matches both "entities" sections (the main one and the post-translucent leftovers). */
    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=entities"))
    private void impetus$phaseEntities(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        impetus$setPhase(ProgramId.Entities);
    }

    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=destroyProgress"))
    private void impetus$phaseBlockDamage(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        impetus$setPhase(ProgramId.DamagedBlock);
    }

    @Inject(method = "renderWorldPass", at = {
            @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=litParticles"),
            @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=particles")})
    private void impetus$phaseParticles(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        impetus$setPhase(ProgramId.TexturedLit);
    }

    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=weather"))
    private void impetus$phaseWeather(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        impetus$setPhase(ProgramId.Weather);
    }

    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=forge_render_last"))
    private void impetus$phaseRenderLast(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        impetus$setPhase(null);
    }

    // --- Mid-frame pipeline stages ---------------------------------------------------------------------------------

    private boolean impetus$shaderHandRendered;

    /**
     * OptiFine's world order around translucents ({@code EntityRenderer.renderWorldPass}):
     * {@code ShadersRender.renderHand0} (solid first-person hand via {@code gbuffers_hand}) → {@code Shaders.preWater()}
     * (depth snapshot + deferred) → translucent terrain. Iris does the same ({@code HandRenderer.renderSolid} before
     * {@code beginTranslucents}). The solid hand MUST render before the deferred chain: deferred packs (Complementary)
     * only write gbuffer data in {@code gbuffers_hand} and do all lighting in {@code deferred*} — a hand drawn later
     * never gets lit and leaks raw buffer data through the composites.
     */
    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=translucent"))
    private void impetus$beginTranslucents(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.beginHand();
            this.impetus$shaderHandRendered = false;
            if (this.renderHand && pipeline.beginHandRendering()) {
                this.impetus$shaderHandRendered = true;
                try {
                    this.impetus$renderFirstPersonItemForShader(partialTicks, pass);
                } finally {
                    pipeline.endHandRendering();
                }
                // Vanilla bound the block atlas for the translucent layer just before this anchor; the hand render
                // bound skin/item textures over it.
                this.mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            }
            pipeline.beginTranslucents();
        }
    }

    /**
     * The composite/final chain runs at the {@code "hand"} anchor, BEFORE vanilla's {@code clear(256)} wipes the depth
     * buffer — OptiFine's {@code Shaders.renderCompositeFinal()} position. The hand itself already rendered pre-deferred.
     */
    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=hand"))
    private void impetus$compositeBeforeHand(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.finishWorldRendering();
        }
    }

    @Redirect(method = "renderWorldPass",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;renderHand(FI)V"))
    private void impetus$skipPostCompositeShaderHand(EntityRenderer renderer, float partialTicks, int pass) {
        if (this.impetus$shaderHandRendered) {
            try {
                this.impetus$renderFirstPersonOverlaysAfterComposite(partialTicks, pass);
            } finally {
                this.impetus$shaderHandRendered = false;
            }
        } else {
            this.renderHand(partialTicks, pass);
        }
    }

    /**
     * The world's projection and modelview must survive this call untouched — translucent terrain renders next with
     * them. OptiFine's {@code Shaders.beginHand()}/{@code endHand()} push/pop both matrices around the hand for the
     * same reason.
     */
    private void impetus$renderFirstPersonItemForShader(float partialTicks, int pass) {
        if (this.debugView) {
            return;
        }

        GlStateManager.matrixMode(5889);
        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(5888);
        GlStateManager.pushMatrix();
        try {
            this.impetus$setupHandProjection(partialTicks, pass);
            this.hurtCameraEffect(partialTicks);
            if (this.mc.gameSettings.viewBobbing) {
                this.applyBobbing(partialTicks);
            }

            boolean sleeping = this.mc.getRenderViewEntity() instanceof EntityLivingBase
                    && ((EntityLivingBase) this.mc.getRenderViewEntity()).isPlayerSleeping();
            boolean renderVanillaHand = !ForgeHooksClient.renderFirstPersonHand(this.mc.renderGlobal, partialTicks, pass);
            if (renderVanillaHand && this.mc.gameSettings.thirdPersonView == 0 && !sleeping
                    && !this.mc.gameSettings.hideGUI && !this.mc.playerController.isSpectator()) {
                this.enableLightmap();
                this.itemRenderer.renderItemInFirstPerson(partialTicks);
                this.disableLightmap();
            }
        } finally {
            GlStateManager.matrixMode(5889);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(5888);
            GlStateManager.popMatrix();
            // OptiFine Shaders.endHand(): restore the standard alpha blend func the hand pass may have changed.
            GlStateManager.blendFunc(770, 771);
        }
    }

    private void impetus$renderFirstPersonOverlaysAfterComposite(float partialTicks, int pass) {
        if (this.debugView) {
            return;
        }

        this.impetus$setupHandProjection(partialTicks, pass);
        boolean sleeping = this.mc.getRenderViewEntity() instanceof EntityLivingBase
                && ((EntityLivingBase) this.mc.getRenderViewEntity()).isPlayerSleeping();
        this.disableLightmap();
        if (this.mc.gameSettings.thirdPersonView == 0 && !sleeping) {
            this.itemRenderer.renderOverlays(partialTicks);
            this.hurtCameraEffect(partialTicks);
        }
        if (this.mc.gameSettings.viewBobbing) {
            this.applyBobbing(partialTicks);
        }
    }

    private void impetus$setupHandProjection(float partialTicks, int pass) {
        GlStateManager.matrixMode(5889);
        GlStateManager.loadIdentity();
        if (this.mc.gameSettings.anaglyph) {
            GlStateManager.translate((float) (-(pass * 2 - 1)) * 0.07f, 0.0f, 0.0f);
        }
        // OptiFine Shaders.applyHandDepth(): squeeze the hand's clip-space Z so it always wins the depth test against
        // world geometry sitting in the gbuffer, and lands at the near plane for composite lighting instead of clipping.
        GlStateManager.scale(1.0f, 1.0f, HAND_DEPTH_MUL);
        Project.gluPerspective(this.getFOVModifier(partialTicks, false),
                (float) this.mc.displayWidth / (float) this.mc.displayHeight,
                0.05f, this.farPlaneDistance * 2.0f);
        GlStateManager.matrixMode(5888);
        GlStateManager.loadIdentity();
        if (this.mc.gameSettings.anaglyph) {
            GlStateManager.translate((float) (pass * 2 - 1) * 0.1f, 0.0f, 0.0f);
        }
    }

    @Inject(method = "renderWorld", at = @At("RETURN"))
    private void impetus$finishShaderFrame(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.finishWorldRendering();
        }
    }
}
