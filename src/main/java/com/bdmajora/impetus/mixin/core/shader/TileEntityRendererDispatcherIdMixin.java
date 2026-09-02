package com.bdmajora.impetus.mixin.core.shader;

import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
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

@Mixin(TileEntityRendererDispatcher.class)
public class TileEntityRendererDispatcherIdMixin {
    @Unique
    private final Deque<Integer> impetus$blockEntityIdStack = new ArrayDeque<>();

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntity;FI)V", at = @At("HEAD"))
    private void impetus$beginBlockEntity(TileEntity tileEntity, float partialTicks, int destroyStage, CallbackInfo ci) {
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        this.impetus$blockEntityIdStack.push(state.getCurrentRenderedBlockEntity());
        state.setCurrentRenderedBlockEntity(WorldRenderingSettings.getBlockEntityId(tileEntity));
        impetus$pushIdToGpu();

        // Same hazard as RenderItem, and worse here. Block entity renderers draw through ModelBase/ModelRenderer,
        // whose vertex data carries no lightmap element either, so a generic array left enabled on slot 9
        // (gl_MultiTexCoord1) flattens their lightmap to one constant and the whole model renders fullbright.
        //
        // ModelRenderer compiles into a display list on first render and glDrawArrays dereferences the bound arrays
        // at COMPILE time, so a single poisoned compile is baked in for the rest of the session rather than for one
        // frame — which is why player heads and other model-based block entities stay lit once they have gone wrong.
        // Resetting before every dispatch guarantees the first compile of each model happens clean.
        IrisRenderingPipeline.resetVanillaVertexArrayState();

        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();

        // Measured, not assumed. Vanilla's render() sets the lightmap coord from getCombinedLight a few instructions
        // AFTER this injection point, so the two light figures logged here answer different questions and the pair is
        // the whole point: `lightCoord` is what the previous draw left in the current-vertex state, `worldLight` is
        // what this block entity is about to be given. Across a bright capture and a normal one at the same spot,
        // worldLight differing means the light engine disagrees about the block, while worldLight holding steady
        // while the model still renders bright means the coordinate is right and something downstream ignored it.
        if (pipeline != null && !pipeline.isCameraPassActive()) {
            ShaderStateProbe.noteShadowDrawSkipped();
        }
        if (pipeline != null && pipeline.isCameraPassActive() && ShaderStateProbe.consumeDrawProbeSlot()) {
            pipeline.logDrawStateProbe(
                    tileEntity.getClass().getSimpleName() + " @" + tileEntity.getPos(),
                    impetus$describeWorldLight(tileEntity));
        }
    }

    /** {@return the light vanilla is about to hand this block entity, in the same 0..240 units as {@code lightCoord}} */
    @Unique
    private static String impetus$describeWorldLight(TileEntity tileEntity) {
        World world = tileEntity.getWorld();
        if (world == null) {
            return "worldLight=unavailable";
        }
        int combined = world.getCombinedLight(tileEntity.getPos(), 0);
        return "worldLight=(" + (combined % 65536) + ", " + (combined / 65536) + ")";
    }

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntity;FI)V", at = @At("RETURN"))
    private void impetus$endBlockEntity(TileEntity tileEntity, float partialTicks, int destroyStage, CallbackInfo ci) {
        CapturedRenderingState.INSTANCE.setCurrentRenderedBlockEntity(
                this.impetus$blockEntityIdStack.isEmpty() ? -1 : this.impetus$blockEntityIdStack.pop());
        // The restore matters as much as the set: without it the last block entity's id stays live over everything
        // drawn after the batch.
        impetus$pushIdToGpu();
    }

    /**
     * Sends the id change to the bound program. Setting it only on {@link CapturedRenderingState} leaves it in Java —
     * the uniform is uploaded when a phase is bound, and one phase covers every block entity in the frame, so the
     * batch would render with whichever one's id happened to be current at phase entry. See
     * {@link IrisRenderingPipeline#refreshDynamicUniforms()}.
     */
    @Unique
    private static void impetus$pushIdToGpu() {
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.refreshDynamicUniforms();
        }
    }
}
