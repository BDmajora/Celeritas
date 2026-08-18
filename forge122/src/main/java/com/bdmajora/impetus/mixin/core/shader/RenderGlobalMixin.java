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
import com.bdmajora.impetus.iris.shaderpack.loading.ProgramId;
import com.bdmajora.impetus.iris.uniforms.CelestialUniforms;

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
    private static final String MOON_TEXTURES_FIELD =
            "Lnet/minecraft/client/renderer/RenderGlobal;MOON_PHASES_TEXTURES:Lnet/minecraft/util/ResourceLocation;";

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

    /**
     * OptiFine's {@code Shaders.preCelestialRotate()} ({@code Shaders.java:3918}): rotate the live modelview by the
     * pack's {@code sunPathRotation} between vanilla's fixed {@code -90°} Y-rotation and its time-of-day X-rotation,
     * so the sun, moon and stars are actually drawn on the tilted arc.
     * <p>
     * Without it the pack's idea of where the sun is and the sun you can see disagree. {@code sunPosition},
     * {@code shadowLightPosition} and the shadow projection already include {@code sunPathRotation} (see
     * {@code CelestialUniforms.getCelestialPosition}), but vanilla's {@code renderSky} does not — so the world is lit
     * and shadowed from one direction while the sun disc is drawn at another. OptiFine cannot get this wrong by
     * construction: it leaves the rotation in the modelview that draws the celestial quads, then reads
     * {@code sunPosition} straight back out of that same matrix in {@code postCelestialRotate()}.
     * <p>
     * Iris does <em>not</em> do this — it rotates only the uniform and the shadow matrix. That is survivable there
     * because modern versions draw the sky through a different renderer and packs that care paint their own; on
     * 1.12 the vanilla sun disc goes through {@code gbuffers_skytextured} and the mismatch is plainly visible. This
     * is not a corner case: 19 of the 22 packs installed here set a non-zero value, most of them -40°.
     */
    @Inject(method = "renderSky(FI)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/WorldClient;getCelestialAngle(F)F", ordinal = 1))
    private void impetus$preCelestialRotate(float partialTicks, int pass, CallbackInfo ci) {
        if (Iris.getRenderingPipeline() == null) {
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

    /**
     * The moon needs its own stage. Vanilla draws sun then moon through one program, so a single anchor would report
     * {@code SUN} for both — and 13 call sites across the installed packs branch on {@code MC_RENDER_STAGE_MOON}
     * (Spooklementary and Pastel key their moon tinting on it). Reporting the sun for the moon is worse than
     * reporting nothing, which is what made this worth splitting rather than approximating.
     */
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

    /**
     * OptiFine 1.12 brackets the actual cloud geometry inside {@code RenderGlobal.renderClouds} with
     * {@code Shaders.beginClouds()}/{@code Shaders.endClouds()}, while modern Iris brackets
     * {@code LevelRenderer.renderClouds} at method entry/return. Do this at the geometry boundary instead of at
     * {@code EntityRenderer}'s profiler label; shader-pack properties can cancel the dispatcher, and a leaked
     * {@code gbuffers_clouds} phase leaves colortex4 selected until the composite chain.
     * <p>
     * These anchors only fire on the vanilla fallback path — {@code RenderGlobalMixin} in {@code core.terrain}
     * normally replaces the geometry with Sodium's cloud renderer and brackets the phase itself.
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

    /**
     * No {@code clouds} directive check here any more: {@code GameSettingsCloudsMixin} folds the pack's setting into
     * {@code shouldRenderClouds()}, so reaching this method already means fancy clouds are the effective mode.
     */
    @Inject(method = "renderCloudsFancy(FIDDD)V", at = @At("HEAD"), require = 0)
    private void impetus$beginFancyClouds(float partialTicks, int pass, double x, double y, double z, CallbackInfo ci) {
        impetus$setPhase(ProgramId.Clouds);
    }

    @Inject(method = "renderCloudsFancy(FIDDD)V", at = @At("RETURN"), require = 0)
    private void impetus$endFancyClouds(float partialTicks, int pass, double x, double y, double z, CallbackInfo ci) {
        impetus$setPhase(null);
    }

    /**
     * Binds {@code gbuffers_line} (falling back to {@code gbuffers_basic}) for the block selection box, matching
     * Iris, which routes vanilla's line render type through that program.
     * <p>
     * Vanilla draws the outline in the {@code "outline"} profiler section, which runs straight after
     * {@code "entities"} and <em>before</em> {@code "destroyProgress"} ({@code EntityRenderer.renderWorldPass}: the
     * outline block, then the debug renderer, then the {@code damagedBlocks} block). So without this anchor the
     * outline inherits whatever {@code impetus$phaseEntities} left bound and is drawn through
     * {@code gbuffers_entities} — a program that shades it as if it were an entity surface and writes it into the
     * entity program's DRAWBUFFERS, normals and material targets included.
     * <p>
     * OptiFine reaches the same place from the other side: its hook inside {@code drawSelectionBox} is
     * {@code Shaders.disableTexture2D()}, which is {@code useProgram(ProgramBasic)} whenever a textured program is
     * current.
     */
    @Inject(method = "drawSelectionBox", at = @At("HEAD"), require = 0)
    private void impetus$beginBlockOutline(net.minecraft.entity.player.EntityPlayer player,
                                           net.minecraft.util.math.RayTraceResult target, int execute,
                                           float partialTicks, CallbackInfo ci) {
        impetus$setPhase(ProgramId.Line);
        // Publish vanilla's outline colour as the *current* fixed-function colour as well as per vertex.
        //
        // Why this is needed at all: the outline is submitted as POSITION_COLOR, so gl_Color is supposed to arrive
        // from the conventional colour array. A pack's gbuffers_basic is then a pure function of it — Pastel's whole
        // fragment stage is `albedo = color` followed by multiplicative lighting (`albedo *= sceneLighting + ...`),
        // with no additive term a zero albedo can escape. Black in must be black out. The outline nevertheless comes
        // out lit and orange, so gl_Color is not the (0, 0, 0, 0.4) vanilla wrote.
        //
        // The mechanism is the conventional/generic attribute aliasing this port already has scars from (see
        // IrisRenderingPipeline.resetVanillaVertexArrayState and the first-person arm): gl_Color aliases generic
        // attribute 3, and when the array behind it does not reach the program the attribute falls back to its
        // CURRENT value — whatever the last GlStateManager.color() left there, typically opaque white. Pastel then
        // shades a white surface with the sunset lighting, which is exactly the colour observed.
        //
        // Setting the current colour costs nothing when the array does work (the array wins), and pins the fallback
        // to the right value when it does not. It is not a substitute for the per-vertex data — vanilla hides the
        // line strip's doubling-back connectors by giving three of its sixteen vertices alpha 0, and a constant
        // colour cannot reproduce that — so if those three connector edges become visible after this, the array is
        // confirmed dead and the aliasing is the thing to fix. Vanilla's own postDraw calls resetColor(), so there
        // is nothing to restore here.
        net.minecraft.client.renderer.GlStateManager.color(0.0F, 0.0F, 0.0F, 0.4F);
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
     * `useProgram(ProgramBasic)`, nothing more. Iris maps the outline through `ShaderKey.LINES`, whose
     * `RenderPipelines.LINES` carries translucent transparency, and `ProgramId.Line`/`ProgramId.Basic` are declared
     * with no `BlendModeOverride` at all, so only an explicit `blend.gbuffers_line`/`blend.gbuffers_basic` directive
     * can turn it off. `setPhase` already applies that directive when a pack declares one.
     */

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

    private static void impetus$setPhase(ProgramId phase) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.setPhase(phase);
        }
    }

    /** Same, for a phase whose {@code renderStage} is finer than its {@link ProgramId} (sky basic covers sky/stars/void). */
    private static void impetus$setPhase(ProgramId phase, int renderStage) {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.setPhase(phase, renderStage);
        }
    }
}
