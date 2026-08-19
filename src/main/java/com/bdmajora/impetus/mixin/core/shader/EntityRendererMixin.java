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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
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

    /**
     * Same, for a phase whose {@code renderStage} its {@link ProgramId} cannot imply. {@code gbuffers_textured_lit}
     * carries both particles and translucent entities here, so the stage has to come from the call site that knows
     * which one it is rather than from a guess in the pipeline.
     */
    private static void impetus$setPhase(ProgramId phase, int renderStage) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.setPhase(phase, renderStage);
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

    // --- Cloud ordering -------------------------------------------------------------------------------------------

    /**
     * Moves the cloud draw to where Iris has it: after the deferred chain, not before terrain.
     * <p>
     * 1.12 renders clouds at one of two call sites depending on the camera's altitude — before terrain when below
     * {@code y=128} ({@code renderWorldPass} line 1361), after translucents when above it ({@code "aboveClouds"}).
     * Modern Minecraft has no such split: the clouds pass is scheduled after the main pass, so it always runs after
     * translucent terrain, which is after Iris takes the {@code depthtex1} snapshot in {@code beginTranslucents}.
     * <p>
     * Packs depend on that. Clouds write depth, so on 1.12's early call site they land in {@code depthtex1}, and any
     * pack that identifies untouched sky as "{@code depthtex1} is still 1.0" then classifies cloud pixels as opaque
     * world geometry. Body Camera's {@code composite} does exactly this: its pass-through branch is
     * {@code Depthv2 == 1 && normal == 0}, and a cloud that misses it falls through to
     * {@code Albedo * (LightmapColor + ShadowColor)} — with no lightmap ever written by {@code gbuffers_clouds}, that
     * is black. Under Iris the same pack is correct, because there the clouds are simply not in that snapshot.
     * <p>
     * Rather than re-implement the draw at a new site, both altitude tests are moved below the world: the early one
     * ({@code < 128}) then never fires and the late one ({@code >= 128}) always does, so vanilla itself issues the
     * clouds from the "aboveClouds" site with its own cloud projection, fog setup and matrix handling intact.
     * <p>
     * Only while a pipeline is active. Vanilla draws translucent terrain with {@code depthMask(false)}, so clouds
     * issued after it would fail to depth-test against water and paint over it; the pipeline turns depth writes back
     * on for translucents ({@code beginTranslucents}, so shader water reaches {@code depthtex0}), which is precisely
     * what makes the late slot safe.
     */
    @ModifyConstant(method = "renderWorldPass", constant = {
            // The two occurrences are the only 128.0D in the method, and are the two halves of the same altitude
            // split: `< 128` guards the early draw, `>= 128` the late one. Both move together.
            @Constant(doubleValue = 128.0D, ordinal = 0),
            @Constant(doubleValue = 128.0D, ordinal = 1)})
    private double impetus$moveCloudsToIrisSlot(double cloudLayer) {
        return Iris.getRenderingPipeline() != null ? Double.NEGATIVE_INFINITY : cloudLayer;
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
        // Iris resolves particles as gbuffers_particles -> gbuffers_textured_lit, so packs that ship the modern
        // program get it and everything else lands where it always did. (OptiFine differs: it uses plain
        // gbuffers_textured for the unlit particle pass and only gbuffers_textured_lit for "litParticles".)
        impetus$setPhase(ProgramId.Particles);
    }

    /**
     * {@code particles.ordering}. 1.12.2's {@code renderWorldPass} already draws particles after the "translucent"
     * anchor, which is where the deferred chain runs — so the vanilla order is Iris's {@code after}, and that is also
     * Iris's default for a pack with a deferred chain. Only {@code before} needs anything done: the vanilla draw is
     * suppressed here and re-issued ahead of the deferred chain.
     * <p>
     * {@code mixed} would need the opaque and translucent particles split across the deferred chain, but 1.12.2
     * emits them from a single {@code renderParticles} call with no such distinction, so it resolves to
     * {@code after} — the closest available ordering, and the one vanilla already gives.
     */
    @Redirect(method = "renderWorldPass",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/ParticleManager;renderParticles"
                            + "(Lnet/minecraft/entity/Entity;F)V"),
            require = 0)
    private void impetus$orderParticles(net.minecraft.client.particle.ParticleManager manager,
                                        net.minecraft.entity.Entity entity, float partialTicks) {
        if (impetus$particlesDrawnEarly) {
            impetus$particlesDrawnEarly = false;
            return;
        }
        manager.renderParticles(entity, partialTicks);
    }

    @Unique
    private boolean impetus$particlesDrawnEarly;

    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING", target = PROFILER_END_START, args = "ldc=weather"))
    private void impetus$phaseWeather(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        impetus$setPhase(ProgramId.Weather);
        // `rain.depth`: vanilla draws rain and snow with depth writes off. A pack that wants precipitation in
        // depthtex (so its composites can find it) asks for them back. No restore is needed — vanilla itself calls
        // depthMask(true) on the line right after renderRainSnow.
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null && pipeline.shouldWriteRainAndSnowToDepthBuffer()) {
            net.minecraft.client.renderer.GlStateManager.depthMask(true);
        }
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
                    this.impetus$renderFirstPersonItemForShader(partialTicks, pass, true);
                } finally {
                    pipeline.endHandRendering();
                }
                // Vanilla bound the block atlas for the translucent layer just before this anchor; the hand render
                // bound skin/item textures over it.
                this.mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            }
            // particles.ordering = before: draw them into the pre-deferred gbuffer so the deferred chain lights them.
            if ("before".equals(pipeline.getParticleOrdering())) {
                net.minecraft.entity.Entity viewEntity = this.mc.getRenderViewEntity();
                if (viewEntity != null) {
                    impetus$setPhase(ProgramId.Particles);
                    this.mc.effectRenderer.renderParticles(viewEntity, partialTicks);
                    this.impetus$particlesDrawnEarly = true;
                }
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
            if (this.impetus$shaderHandRendered && pipeline.beginHandTranslucentRendering()) {
                try {
                    this.impetus$renderFirstPersonItemForShader(partialTicks, pass, false);
                } finally {
                    pipeline.endHandRendering();
                }
                this.mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            }
            pipeline.finishWorldRendering();
            // The block selection box for packs with no gbuffers_line was skipped during the world pass and lands
            // here instead, so it darkens the finished image rather than the albedo the composite chain relights.
            // See DeferredBlockOutline for why this deviates from Iris/OptiFine on 1.12.
            com.bdmajora.impetus.iris.pipeline.DeferredBlockOutline.drawIfPending();
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
    private void impetus$renderFirstPersonItemForShader(float partialTicks, int pass, boolean fireForgeHook) {
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
            boolean renderVanillaHand = !fireForgeHook
                    || !ForgeHooksClient.renderFirstPersonHand(this.mc.renderGlobal, partialTicks, pass);
            if (renderVanillaHand && this.mc.gameSettings.thirdPersonView == 0 && !sleeping
                    && !this.mc.gameSettings.hideGUI && !this.mc.playerController.isSpectator()) {
                this.enableLightmap();
                this.itemRenderer.renderItemInFirstPerson(partialTicks);
                // Unit 0 still holds whatever the arm draw sampled; identify it once (see the probe's javadoc).
                IrisRenderingPipeline.logHandBoundTextureProbe();
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
        // Safety net: this is the frame's last word. If the composite anchor above never ran, a captured outline is
        // still pending — drop it rather than let a stale capture replay into some later frame with the wrong
        // matrices. Costs at most one frame's outline on a path that already skipped the composite chain.
        com.bdmajora.impetus.iris.pipeline.DeferredBlockOutline.discard();
    }
}
