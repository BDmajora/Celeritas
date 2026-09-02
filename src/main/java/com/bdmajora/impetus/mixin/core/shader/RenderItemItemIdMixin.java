package com.bdmajora.impetus.mixin.core.shader;

import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bdmajora.impetus.iris.Iris;
import com.bdmajora.impetus.iris.devtool.ShaderStateProbe;
import com.bdmajora.impetus.iris.material.WorldRenderingSettings;
import com.bdmajora.impetus.iris.pipeline.IrisRenderingPipeline;
import com.bdmajora.impetus.iris.uniforms.CapturedRenderingState;

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

        // Item models are submitted through client arrays, which on the compatibility profile alias generic
        // attribute slots 8..15 (gl_MultiTexCoord0..7). A generic array left enabled on one of those slots BEATS the
        // aliased conventional array and flattens the attribute to a single value for the entire draw — the exact
        // mechanism documented on IrisRenderingPipeline#resetVanillaVertexArrayState, which already guards the
        // first-person arm against it for gl_MultiTexCoord0.
        //
        // Slot 9 is gl_MultiTexCoord1: the lightmap. DefaultVertexFormats.ITEM carries no lightmap element, so an
        // item model has nothing per-vertex to fall back on — flatten that slot and every fragment lights from one
        // constant coordinate, which reads as fullbright. Chunk terrain is immune because Sodium draws it from its
        // own VAO with a real per-vertex lightmap. That asymmetry is the tell: servers that build scenery out of
        // custom item models in item frames light up while the vanilla blocks beside them stay correct.
        IrisRenderingPipeline.resetVanillaVertexArrayState();

        // No phase is bound here. OptiFine does not set one at the item level either: an item model is always drawn
        // inside some enclosing renderer (an item frame, a held item on an armor stand, a dropped item entity, a block
        // entity), and Shaders.nextEntity/nextBlockEntity have already selected that renderer's program by the time
        // RenderItem runs. Impetus now does the same in RenderManagerEntityIdMixin and
        // TileEntityRendererDispatcherIdMixin, so binding anything here would override the enclosing choice.

        // Sample the GL state this draw actually lights from. Gated on world rendering: GUI item draws run after the
        // world with no phase bound, and being first in line after the screenshot they claimed every sample of an
        // earlier capture, so the world geometry under investigation was never measured at all.
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
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
    }
}
