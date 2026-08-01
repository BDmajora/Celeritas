package com.bdmajora.impetus.mixin.core.shader;

import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bdmajora.impetus.iris.Iris;
import com.bdmajora.impetus.iris.pipeline.IrisRenderingPipeline;
import com.bdmajora.impetus.iris.pipeline.VanillaFeatureToggles;
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

    /**
     * Binds {@code gbuffers_line} (falling back to {@code gbuffers_basic}) for the block selection box, matching
     * Iris, which routes vanilla's line render type through that program.
     * <p>
     * Vanilla draws the outline in the {@code "outline"} profiler section, which sits between {@code destroyProgress}
     * and {@code particles} — so without this the phase set by {@code impetus$phaseBlockDamage} is still active and
     * the outline is drawn through {@code gbuffers_damagedblock}. That is not merely the wrong shading: on the
     * OptiFine code path a pack points damagedblock at the block-damage overlay buffer (Photon:
     * {@code RENDERTARGETS: 3}), and its deferred pass then folds that buffer into the scene *additively* —
     * {@code albedo = overlay_id == 0u ? albedo + overlays.rgb : albedo}. The outline therefore stopped being a line
     * and became a red stain added onto whatever it crossed, which is why it read as "red borders on plants".
     */
    @Inject(method = "drawSelectionBox", at = @At("HEAD"), require = 0)
    private void impetus$beginBlockOutline(net.minecraft.entity.player.EntityPlayer player,
                                           net.minecraft.util.math.RayTraceResult target, int execute,
                                           float partialTicks, CallbackInfo ci) {
        impetus$setPhase(ProgramId.Line);
    }

    @Inject(method = "drawSelectionBox", at = @At("RETURN"), require = 0)
    private void impetus$endBlockOutline(net.minecraft.entity.player.EntityPlayer player,
                                         net.minecraft.util.math.RayTraceResult target, int execute,
                                         float partialTicks, CallbackInfo ci) {
        impetus$setPhase(null);
    }

    /**
     * Keeps GL blending off for the outline once a pack program is consuming it — the same rule as
     * {@code RenderPlayerArmBlendMixin}. Vanilla asks for {@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA} to draw the box at
     * 40% black, but {@code gbuffers_basic} does not emit a colour on the OptiFine path: it emits packed gbuffer data
     * whose alpha is {@code pack_unorm_2x8(adjusted_light_levels)}. Blending against that mixes the outline's packed
     * data with the terrain's by a factor that means nothing, and packed pairs do not survive a linear mix. The line
     * comes out fully opaque instead of 40%, which is the same trade Iris makes by drawing lines through a program at
     * all. Left alone when the pack has no {@code gbuffers_line}/{@code gbuffers_basic}, since the fixed-function path
     * still wants vanilla's blending.
     */
    @Redirect(method = "drawSelectionBox",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;enableBlend()V"),
            require = 0)
    private void impetus$keepOutlineUnblended() {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline == null || !pipeline.hasGbufferProgram(ProgramId.Line)) {
            net.minecraft.client.renderer.GlStateManager.enableBlend();
        }
    }

    /**
     * {@code sky = false}: draw no vanilla sky geometry at all. The pack paints the sky in its composite chain
     * instead, so the sky dome, void plane, sun, moon and stars are all skipped together — this is a stronger
     * statement than the individual {@code sun}/{@code moon}/{@code stars} switches, which only remove one body.
     */
    @Inject(method = "renderSky(FI)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void impetus$suppressSky(float partialTicks, int pass, CallbackInfo ci) {
        if (!VanillaFeatureToggles.shouldRenderSky()) {
            ci.cancel();
        }
    }

    /**
     * {@code backFace.<layer>}: vanilla culls back faces for every terrain layer. A pack that shades both sides of a
     * face asks for a layer's back faces to be kept, so culling is turned off around that layer's draw and restored
     * afterwards.
     */
    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("HEAD"), require = 0)
    private void impetus$applyBackFaceCulling(net.minecraft.util.BlockRenderLayer layer, double partialTicks, int pass,
                                              net.minecraft.entity.Entity entity,
                                              org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null && !pipeline.shouldCullBackFaces(layer.ordinal())) {
            net.minecraft.client.renderer.GlStateManager.disableCull();
            impetus$restoreCull = true;
        }
    }

    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("RETURN"), require = 0)
    private void impetus$restoreBackFaceCulling(net.minecraft.util.BlockRenderLayer layer, double partialTicks,
                                                int pass, net.minecraft.entity.Entity entity,
                                                org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
        if (impetus$restoreCull) {
            impetus$restoreCull = false;
            net.minecraft.client.renderer.GlStateManager.enableCull();
        }
    }

    @Unique
    private boolean impetus$restoreCull;

    /**
     * {@code skipAllRendering}: draw no terrain at all, leaving the composite chain to produce the whole image.
     * Debug/benchmark packs use this.
     */
    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void impetus$skipTerrain(net.minecraft.util.BlockRenderLayer layer, double partialTicks, int pass,
                                     net.minecraft.entity.Entity entity,
                                     org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null && pipeline.skipAllRendering()) {
            cir.setReturnValue(0);
        }
    }

    /**
     * The {@code sun}, {@code moon} and {@code stars} toggles. 1.12.2 draws all three inline in {@code renderSky},
     * so each is suppressed at its own draw: the celestial quads by swapping in a fully transparent texture (they
     * are drawn additively, so a transparent sample contributes nothing), the star field by skipping its draw call.
     */
    @Redirect(method = "renderSky(FI)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/TextureManager;bindTexture"
                            + "(Lnet/minecraft/util/ResourceLocation;)V"),
            require = 0)
    private void impetus$suppressCelestialBody(net.minecraft.client.renderer.texture.TextureManager manager,
                                               net.minecraft.util.ResourceLocation location) {
        boolean isSun = location.getPath().endsWith("sun.png");
        boolean isMoon = location.getPath().endsWith("moon_phases.png");
        if ((isSun && !VanillaFeatureToggles.shouldRenderSun())
                || (isMoon && !VanillaFeatureToggles.shouldRenderMoon())) {
            manager.bindTexture(impetus$transparentTexture());
            return;
        }
        manager.bindTexture(location);
    }

    @Redirect(method = "renderSky(FI)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/vertex/VertexBuffer;drawArrays(I)V"),
            require = 0)
    private void impetus$suppressStarVbo(net.minecraft.client.renderer.vertex.VertexBuffer buffer, int mode) {
        if (VanillaFeatureToggles.shouldRenderStars()) {
            buffer.drawArrays(mode);
        }
    }

    @Redirect(method = "renderSky(FI)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;callList(I)V"),
            require = 0)
    private void impetus$suppressStarList(int list) {
        if (VanillaFeatureToggles.shouldRenderStars()) {
            net.minecraft.client.renderer.GlStateManager.callList(list);
        }
    }

    /** A 1x1 fully transparent texture, so a suppressed celestial quad still draws but contributes nothing. */
    @Unique
    private static net.minecraft.util.ResourceLocation impetus$transparentTexture() {
        if (impetus$transparent == null) {
            net.minecraft.client.renderer.texture.DynamicTexture texture =
                    new net.minecraft.client.renderer.texture.DynamicTexture(1, 1);
            texture.getTextureData()[0] = 0;
            texture.updateDynamicTexture();
            impetus$transparent = net.minecraft.client.Minecraft.getMinecraft().getTextureManager()
                    .getDynamicTextureLocation("impetus_transparent", texture);
        }
        return impetus$transparent;
    }

    @Unique
    private static net.minecraft.util.ResourceLocation impetus$transparent;

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
