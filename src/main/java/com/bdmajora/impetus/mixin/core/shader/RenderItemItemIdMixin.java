package com.bdmajora.impetus.mixin.core.shader;

import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bdmajora.impetus.umbra.Umbra;
import com.bdmajora.impetus.umbra.devtool.ShaderStateProbe;
import com.bdmajora.impetus.umbra.material.WorldRenderingSettings;
import com.bdmajora.impetus.umbra.pipeline.UmbraRenderingPipeline;
import com.bdmajora.impetus.umbra.uniforms.CapturedRenderingState;

import java.util.ArrayDeque;
import java.util.Deque;

@Mixin(RenderItem.class)
public class RenderItemItemIdMixin {
    @Unique
    private final Deque<Integer> impetus$itemIdStack = new ArrayDeque<>();

    @Inject(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/IBakedModel;)V",
            at = @At("HEAD"))
    private void impetus$beginItem(ItemStack stack, IBakedModel model, CallbackInfo ci) {
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        this.impetus$itemIdStack.push(state.getCurrentRenderedItem());
        state.setCurrentRenderedItem(WorldRenderingSettings.getItemId(stack));
        impetus$pushIdToGpu();

        // Item models are submitted through client arrays, which on the compatibility profile alias generic
        // attribute slots 8..15 (gl_MultiTexCoord0..7). A generic array left enabled on one of those slots BEATS the
        // aliased conventional array and flattens the attribute to a single value for the entire draw — the exact
        // mechanism documented on UmbraRenderingPipeline#resetVanillaVertexArrayState, which already guards the
        // first-person arm against it for gl_MultiTexCoord0.
        //
        // Slot 9 is gl_MultiTexCoord1: the lightmap. DefaultVertexFormats.ITEM carries no lightmap element, so an
        // item model has nothing per-vertex to fall back on — flatten that slot and every fragment lights from one
        // constant coordinate, which reads as fullbright. Chunk terrain is immune because Sodium draws it from its
        // own VAO with a real per-vertex lightmap. That asymmetry is the tell: servers that build scenery out of
        // custom item models in item frames light up while the vanilla blocks beside them stay correct.
        UmbraRenderingPipeline.resetVanillaVertexArrayState();

        // No phase is bound here. OptiFine does not set one at the item level either: an item model is always drawn
        // inside some enclosing renderer (an item frame, a held item on an armor stand, a dropped item entity, a block
        // entity), and Shaders.nextEntity/nextBlockEntity have already selected that renderer's program by the time
        // RenderItem runs, so binding anything here would override the enclosing choice.
        //
        // Impetus does NOT yet make that enclosing choice per object: EntityRendererMixin sets ProgramId.Entities once
        // for the whole pass and ProgramId.BlockEntities (gbuffers_block) is never selected in the camera pass at all,
        // so block entities are drawn by gbuffers_entities. Umbra routes them separately. Restoring that means a
        // per-object setPhase, which is the change that previously shredded water and terrain by firing inside the
        // shadow pass — setPhase now carries an isShadowPass() guard, so it is safe to retry, but as its own change.

        // Sample the GL state this draw actually lights from. Gated on world rendering: GUI item draws run after the
        // world with no phase bound, and being first in line after the screenshot they claimed every sample of an
        // earlier capture, so the world geometry under investigation was never measured at all.
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null && !pipeline.isCameraPassActive()) {
            ShaderStateProbe.noteShadowDrawSkipped();
        }
        if (pipeline != null && pipeline.isCameraPassActive() && ShaderStateProbe.consumeDrawProbeSlot()) {
            pipeline.logDrawStateProbe("item " + stack.getItem().getRegistryName(), null);
        }
    }

    @Inject(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/IBakedModel;)V",
            at = @At("RETURN"))
    private void impetus$endItem(ItemStack stack, IBakedModel model, CallbackInfo ci) {
        CapturedRenderingState.INSTANCE.setCurrentRenderedItem(
                this.impetus$itemIdStack.isEmpty() ? -1 : this.impetus$itemIdStack.pop());
        // The restore matters as much as the set. This is the case the ride scenery hits: an item model nested inside
        // an item frame or armor stand leaves its id live over the rest of the batch if it is not popped to the GPU,
        // so one emissive custom item makes every entity drawn after it emissive too.
        impetus$pushIdToGpu();
    }

    /**
     * Sends the id change to the bound program. Setting it only on {@link CapturedRenderingState} leaves it in Java —
     * the uniform is uploaded when a phase is bound, and one phase covers every item in the frame, so the batch would
     * render with whichever item's id happened to be current at phase entry. See
     * {@link UmbraRenderingPipeline#refreshDynamicUniforms()}.
     */
    @Unique
    private static void impetus$pushIdToGpu() {
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.refreshDynamicUniforms();
        }
    }
}
