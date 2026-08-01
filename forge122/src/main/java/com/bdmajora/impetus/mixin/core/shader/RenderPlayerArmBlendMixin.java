package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps GL blending off while a shader pack's {@code gbuffers_hand} pass draws the first-person arm.
 * <p>
 * {@code RenderPlayer.renderRight/LeftArm} wraps the arm and armwear boxes in
 * {@code enableBlend()}/{@code disableBlend()}, because 1.12.2 draws both in one pass and leans on blending plus the
 * fixed-function alpha test to hide the armwear's transparent texels. Modern Minecraft — which Iris is written
 * against — splits them into a solid {@code RenderType} (blending OFF, and the only thing that reaches
 * {@code gbuffers_hand}) and a translucent one that goes to {@code gbuffers_hand_water}. So on Iris nothing ever
 * blends into {@code gbuffers_hand}'s targets, and packs are written assuming that.
 * <p>
 * That assumption matters because a modern pack's gbuffer output is not a colour. Photon writes
 * {@code gbuffer_data_0 = (pack_unorm_2x8(base_color.rg), pack_unorm_2x8(base_color.b, material_mask),
 * pack_unorm_2x8(normal), pack_unorm_2x8(light_levels))} — so the "alpha" GL blends against is the packed
 * <em>light levels</em>, not coverage, and the other three components are bit-packed pairs that do not survive a
 * linear mix. Blending here silently replaces most of the arm's gbuffer data with whatever is already in the target,
 * and because that destination changes at the base arm's silhouette (arm gbuffer data inside, terrain gbuffer data
 * outside) the arm comes out as a correctly-shaded centre inside a differently-coloured rim.
 * <p>
 * Scoped to the arm only, and only while the pack's hand pass is actually bound — the render stage is set by
 * {@code IrisRenderingPipeline.beginHandRendering()} and cleared by {@code endHandRendering()}, so third-person
 * bodies, other players and the no-shaders path are untouched.
 */
@Mixin(RenderPlayer.class)
public class RenderPlayerArmBlendMixin {
    /** {@code MC_RENDER_STAGE_HAND_SOLID}; see {@code ShaderMacros}. */
    private static final int HAND_SOLID = 16;

    @Redirect(
            method = {"renderRightArm", "renderLeftArm"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;enableBlend()V"),
            require = 0)
    private void impetus$keepArmUnblended() {
        if (CapturedRenderingState.INSTANCE.getRenderStage() != HAND_SOLID) {
            GlStateManager.enableBlend();
        }
    }
}
