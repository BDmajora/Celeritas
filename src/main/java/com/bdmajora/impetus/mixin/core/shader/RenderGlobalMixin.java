package com.bdmajora.impetus.mixin.core.shader;

import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bdmajora.impetus.umbra.Umbra;
import com.bdmajora.impetus.umbra.pipeline.DeferredBlockOutline;
import com.bdmajora.impetus.umbra.pipeline.UmbraRenderingPipeline;
import com.bdmajora.impetus.umbra.pipeline.VanillaFeatureToggles;
import com.bdmajora.impetus.umbra.shaderpack.loading.ProgramId;
import com.bdmajora.impetus.umbra.uniforms.CelestialUniforms;

// switches the sky phase to gbuffers_skytextured for the textured celestial bodies (sun and moon)
// inside renderSky, and back to gbuffers_skybasic once they are drawn - OptiFine's preCelestialRotate
// sun/moon program split
// the outer "sky" profiler anchor in EntityRendererMixin has already selected skybasic for the sky
// discs and horizon
@Mixin(RenderGlobal.class)
public class RenderGlobalMixin {
    private static final String SUN_TEXTURES_FIELD =
            "Lnet/minecraft/client/renderer/RenderGlobal;SUN_TEXTURES:Lnet/minecraft/util/ResourceLocation;";
    private static final String MOON_TEXTURES_FIELD =
            "Lnet/minecraft/client/renderer/RenderGlobal;MOON_PHASES_TEXTURES:Lnet/minecraft/util/ResourceLocation;";

    // right before the vanilla sky disc VBO is drawn, with the skybasic phase already active, draw
    // OptiFine's horizon fill so the thin uncovered band at the horizon lands in colortex1 with the
    // atmospheric sky colour instead of stale HDR
    // matches OptiFine's Shaders.preSkyList() call site, immediately before skyVBO.bindBuffer()
    @Inject(method = "renderSky(FI)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/vertex/VertexBuffer;bindBuffer()V", ordinal = 0))
    private void impetus$drawHorizon(float partialTicks, int pass, CallbackInfo ci) {
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.drawSkyHorizon();
        }
    }

    // OptiFine's Shaders.preCelestialRotate() (Shaders.java:3918): rotate the live modelview by the
    // pack's sunPathRotation between vanilla's fixed -90 degree Y-rotation and its time-of-day
    // X-rotation, so the sun, moon and stars are actually drawn on the tilted arc
    // without it the pack's idea of where the sun is and the sun you can see disagree: sunPosition,
    // shadowLightPosition and the shadow projection already include sunPathRotation (see
    // CelestialUniforms.getCelestialPosition), but vanilla's renderSky does not, so the world is lit and
    // shadowed from one direction while the sun disc is drawn at another
    // OptiFine cannot get this wrong by construction: it leaves the rotation in the modelview that draws
    // the celestial quads, then reads sunPosition straight back out of that same matrix in
    // postCelestialRotate()
    // Umbra does *not* do this - it rotates only the uniform and the shadow matrix - which is survivable
    // there because modern versions draw the sky through a different renderer and packs that care paint
    // their own; on 1.12 the vanilla sun disc goes through gbuffers_skytextured and the mismatch is
    // plainly visible
    // this is not a corner case: 19 of the 22 packs installed here set a non-zero value, most of them -40
    @Inject(method = "renderSky(FI)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/WorldClient;getCelestialAngle(F)F", ordinal = 1))
    private void impetus$preCelestialRotate(float partialTicks, int pass, CallbackInfo ci) {
        if (Umbra.getRenderingPipeline() == null) {
            return;
        }
        float rotation = CelestialUniforms.getSunPathRotation();
        if (rotation != 0.0f) {
            net.minecraft.client.renderer.GlStateManager.rotate(rotation, 0.0F, 0.0F, 1.0F);
        }
    }

    @Inject(method = "renderSky(FI)V",
            at = @At(value = "FIELD", target = SUN_TEXTURES_FIELD, opcode = org.objectweb.asm.Opcodes.GETSTATIC))
    private void impetus$beginSunMoon(float partialTicks, int pass, CallbackInfo ci) {
        impetus$setPhase(ProgramId.SkyTextured, 4); // MC_RENDER_STAGE_SUN
    }

    // the moon needs its own stage
    // vanilla draws sun then moon through one program, so a single anchor would report SUN for both -
    // and 13 call sites across the installed packs branch on MC_RENDER_STAGE_MOON, with Spooklementary
    // and Pastel keying their moon tinting on it
    // reporting the sun for the moon is worse than reporting nothing, which is what made this worth
    // splitting rather than approximating
    @Inject(method = "renderSky(FI)V",
            at = @At(value = "FIELD", target = MOON_TEXTURES_FIELD, opcode = org.objectweb.asm.Opcodes.GETSTATIC),
            require = 0)
    private void impetus$beginMoon(float partialTicks, int pass, CallbackInfo ci) {
        impetus$setPhase(ProgramId.SkyTextured, 5); // MC_RENDER_STAGE_MOON
    }

    @Inject(method = "renderSky(FI)V",
            slice = @Slice(from = @At(value = "FIELD", target = SUN_TEXTURES_FIELD,
                    opcode = org.objectweb.asm.Opcodes.GETSTATIC)),
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;disableTexture2D()V", ordinal = 0))
    private void impetus$endSunMoon(float partialTicks, int pass, CallbackInfo ci) {
        // This injection point is vanilla's disableTexture2D() right after the sun/moon quads, which is exactly where
        // renderSky begins the star field. The stars go back through gbuffers_skybasic, so ProgramId alone cannot tell
        // a pack it is drawing stars rather than the sky dome — Clarity emits its star field only under
        // MC_RENDER_STAGE_STARS, so the finer stage has to be published explicitly here.
        impetus$setPhase(ProgramId.SkyBasic, 6); // MC_RENDER_STAGE_STARS
    }

    // OptiFine 1.12 brackets the actual cloud geometry inside RenderGlobal.renderClouds with
    // Shaders.beginClouds()/Shaders.endClouds(), while modern Umbra brackets LevelRenderer.renderClouds
    // at method entry and return
    // this does it at the geometry boundary rather than at EntityRenderer's profiler label, because
    // shader-pack properties can cancel the dispatcher and a leaked gbuffers_clouds phase leaves
    // colortex4 selected until the composite chain
    // these anchors only fire on the vanilla fallback path - RenderGlobalMixin in core.terrain normally
    // replaces the geometry with Sodium's cloud renderer and brackets the phase itself
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

    // no clouds directive check here any more: GameSettingsCloudsMixin folds the pack's setting into
    // shouldRenderClouds(), so reaching this method already means fancy clouds are the effective mode
    @Inject(method = "renderCloudsFancy(FIDDD)V", at = @At("HEAD"), require = 0)
    private void impetus$beginFancyClouds(float partialTicks, int pass, double x, double y, double z, CallbackInfo ci) {
        impetus$setPhase(ProgramId.Clouds);
    }

    @Inject(method = "renderCloudsFancy(FIDDD)V", at = @At("RETURN"), require = 0)
    private void impetus$endFancyClouds(float partialTicks, int pass, double x, double y, double z, CallbackInfo ci) {
        impetus$setPhase(null);
    }

    // binds gbuffers_line, falling back to gbuffers_basic, for the block selection box - matching Umbra,
    // which routes vanilla's line render type through that program
    // vanilla draws the outline in the "outline" profiler section, which runs straight after "entities"
    // and *before* "destroyProgress" (in EntityRenderer.renderWorldPass: the outline block, then the
    // debug renderer, then the damagedBlocks block)
    // so without this anchor the outline inherits whatever impetus$phaseEntities left bound and is drawn
    // through gbuffers_entities - a program that shades it as if it were an entity surface and writes it
    // into the entity program's DRAWBUFFERS, normals and material targets included
    // OptiFine reaches the same place from the other side: its hook inside drawSelectionBox is
    // Shaders.disableTexture2D(), which is useProgram(ProgramBasic) whenever a textured program is current
    @Inject(method = "drawSelectionBox", at = @At("HEAD"), cancellable = true, require = 0)
    private void impetus$beginBlockOutline(net.minecraft.entity.player.EntityPlayer player,
                                           net.minecraft.util.math.RayTraceResult target, int execute,
                                           float partialTicks, CallbackInfo ci) {
        // Only route the outline through the pack when the pack actually ships `gbuffers_line`.
        //
        // Umbra parity for packs that wrote one: those packs know the selection box arrives here and handle it
        // deliberately (Complementary/Spooklementary have a whole `SELECT_OUTLINE` option group keyed on recognising
        // vanilla's (0,0,0,0.4) vertex colour). Nothing changes for them.
        //
        // For a pack with no `gbuffers_line`, OptiFine's fallback chain lands the outline in `gbuffers_basic` — a
        // program written for sky/cloud/debug geometry that has no idea what it is being handed. RedHat's is the
        // worst case and is why this exists; its entire fragment stage is
        //     gl_FragData[0] = vec4(0.0, 0.0, 0.0, 1.0);   // colortex0, alpha 1.0
        //     gl_FragData[1] = vec4(0.0, 1.0, 0.0, 1.0);   // colortex4 = (skylight, mat, blocklight, 1)
        // with `varying vec4 color` declared and never read. So it discards vanilla's 0.4 alpha *and* the five
        // alpha-0 vertices `drawBoundingBox` uses to hide the line strip's doubling-back connectors, then stamps
        // "land material, zero skylight, zero blocklight" into the material buffer. It writes neither the normal
        // (colortex2) nor the specular (colortex6) target, and vanilla draws the box with `depthMask(false)`, so the
        // deferred pass relights those pixels using the *torch's* normal and specular against a black albedo. The
        // multiplicative term goes to black; the additive specular term does not — which is the one-pixel red line
        // across the torch, and why it only appears at certain view angles (specular is view-dependent).
        //
        // Falling back to fixed-function is what vanilla does and what the pack was written to coexist with: the box
        // draws with vanilla's own blend, its per-vertex colour and alpha intact (so the connectors stay hidden), and
        // into the fixed-function draw-buffer mask only, so it cannot corrupt material data it has no values for.
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline == null || DeferredBlockOutline.isReplaying()) {
            // No pack, or this *is* the post-composite replay: let vanilla draw exactly as it always does.
            return;
        }
        if (pipeline.hasDirectGbufferProgram(ProgramId.Line)) {
            impetus$setPhase(ProgramId.Line);
            return;
        }
        // No gbuffers_line: defer the whole draw past the composite chain (see DeferredBlockOutline) and skip it
        // here. Drawing it now would only tint albedo, which the composite then relights.
        DeferredBlockOutline.capture(player, target, partialTicks);
        ci.cancel();
    }

    @Inject(method = "drawSelectionBox", at = @At("RETURN"), require = 0)
    private void impetus$endBlockOutline(net.minecraft.entity.player.EntityPlayer player,
                                         net.minecraft.util.math.RayTraceResult target, int execute,
                                         float partialTicks, CallbackInfo ci) {
        impetus$setPhase(null);
    }

    /*
     * There is deliberately no blend override here. Vanilla's own `enableBlend()` +
     * `tryBlendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ZERO)` must survive the phase switch, because
     * `RenderGlobal.drawBoundingBox` encodes the box as a single GL_LINE_STRIP and uses *alpha* to hide the strip's
     * connector segments: three of its sixteen vertices carry `alpha = 0.0F` where the strip has to double back, so
     * the retraced segments fade out instead of being drawn. Suppressing the blend turns those into full-strength
     * lines and, worse, makes the whole outline opaque — which means it stops being a 40% darkening of colortex0 and
     * starts *replacing* the gbuffer. A pack whose `gbuffers_basic` writes DRAWBUFFERS 0/3/6/7 (Pastel's does, under
     * ADVANCED_MATERIALS) then stamps `vec3(0.0)` into the normal target along every edge, and the deferred pass
     * normalizes a zero normal, so the outline came back out of the lighting pass as a bright red/white/orange cage
     * instead of a dark line.
     *
     * Both references keep the blend. OptiFine 1.12 (`RenderGlobal.drawSelectionBox`) leaves vanilla's
     * `enableBlend()` untouched and only swaps the program — its shader hook is `Shaders.disableTexture2D()`, i.e.
     * `useProgram(ProgramBasic)`, nothing more. Umbra maps the outline through `ShaderKey.LINES`, whose
     * `RenderPipelines.LINES` carries translucent transparency, and `ProgramId.Line`/`ProgramId.Basic` are declared
     * with no `BlendModeOverride` at all, so only an explicit `blend.gbuffers_line`/`blend.gbuffers_basic` directive
     * can turn it off. `setPhase` already applies that directive when a pack declares one.
     */

    // sky = false: draw no vanilla sky geometry at all
    // the pack paints the sky in its composite chain instead, so the sky dome, void plane, sun, moon and
    // stars are all skipped together - a stronger statement than the individual sun/moon/stars switches,
    // which only remove one body
    @Inject(method = "renderSky(FI)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void impetus$suppressSky(float partialTicks, int pass, CallbackInfo ci) {
        if (!VanillaFeatureToggles.shouldRenderSky()) {
            ci.cancel();
        }
    }

    // backFace.<layer>: vanilla culls back faces for every terrain layer
    // a pack that shades both sides of a face asks for a layer's back faces to be kept, so culling is
    // turned off around that layer's draw and restored afterwards
    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("HEAD"), require = 0)
    private void impetus$applyBackFaceCulling(net.minecraft.util.BlockRenderLayer layer, double partialTicks, int pass,
                                              net.minecraft.entity.Entity entity,
                                              org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
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

    // skipAllRendering: draw no terrain at all, leaving the composite chain to produce the whole image
    // debug and benchmark packs use this
    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void impetus$skipTerrain(net.minecraft.util.BlockRenderLayer layer, double partialTicks, int pass,
                                     net.minecraft.entity.Entity entity,
                                     org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null && pipeline.skipAllRendering()) {
            cir.setReturnValue(0);
        }
    }

    // the sun, moon and stars toggles
    // 1.12.2 draws all three inline in renderSky, so each is suppressed at its own draw: the celestial
    // quads by swapping in a fully transparent texture - they are drawn additively, so a transparent
    // sample contributes nothing - and the star field by skipping its draw call
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

    // A 1x1 fully transparent texture, so a suppressed celestial quad still draws but contributes nothing.
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

    private static void impetus$setPhase(ProgramId phase) {
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.setPhase(phase);
        }
    }

    // Same, for a phase whose renderStage is finer than its ProgramId (sky basic covers sky/stars/void).
    private static void impetus$setPhase(ProgramId phase, int renderStage) {
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.setPhase(phase, renderStage);
        }
    }
}
