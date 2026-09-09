package com.bdmajora.impetus.mixin.core.shader;

import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bdmajora.impetus.umbra.Umbra;
import com.bdmajora.impetus.umbra.material.WorldRenderingSettings;
import com.bdmajora.impetus.umbra.pipeline.UmbraRenderingPipeline;
import com.bdmajora.impetus.umbra.uniforms.CapturedRenderingState;

import java.util.ArrayDeque;
import java.util.Deque;

@Mixin(RenderManager.class)
public class RenderManagerEntityIdMixin {
    @Unique
    private final Deque<Integer> impetus$entityIdStack = new ArrayDeque<>();

    @Inject(method = "renderEntityStatic(Lnet/minecraft/entity/Entity;FZ)V", at = @At("HEAD"))
    private void impetus$beginEntity(Entity entity, float partialTicks, boolean p_188388_3_, CallbackInfo ci) {
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        this.impetus$entityIdStack.push(state.getCurrentRenderedEntity());
        state.setCurrentRenderedEntity(WorldRenderingSettings.getEntityId(entity));
        impetus$pushIdToGpu();
    }

    @Inject(method = "renderEntityStatic(Lnet/minecraft/entity/Entity;FZ)V", at = @At("RETURN"))
    private void impetus$endEntity(Entity entity, float partialTicks, boolean p_188388_3_, CallbackInfo ci) {
        CapturedRenderingState.INSTANCE.setCurrentRenderedEntity(
                this.impetus$entityIdStack.isEmpty() ? -1 : this.impetus$entityIdStack.pop());
        // The restore matters as much as the set: without it the last entity's id stays live over everything drawn
        // after the batch.
        impetus$pushIdToGpu();
    }

    // sends the id change to the bound program
    // setting it only on CapturedRenderingState leaves it in Java: the uniform is uploaded when a
    // phase is bound, and one phase covers every entity in the frame, so the batch would render with
    // whichever entity's id happened to be current at phase entry
    // see UmbraRenderingPipeline#refreshDynamicUniforms()
    @Unique
    private static void impetus$pushIdToGpu() {
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.refreshDynamicUniforms();
        }
    }
}
