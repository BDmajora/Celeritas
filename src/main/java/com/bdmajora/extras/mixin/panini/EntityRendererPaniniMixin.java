package com.bdmajora.extras.mixin.panini;

import com.bdmajora.extras.client.PaniniProjection;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the Panini projection post-effect.
 *
 * <p>Two hooks. The first reads the effective field of view out of the world projection as it is
 * built — the FOV setting alone is not enough, because sprinting, speed effects and the nausea warp
 * all scale it, and Panini has to match what was actually rendered or the image swims. The second
 * runs the pass at exactly the point vanilla runs its own shader group: after the world and the
 * entity-outline composite, before the GUI, with the main framebuffer still bound.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererPaniniMixin {
    @WrapOperation(
            method = "setupCameraTransform",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V",
                    remap = false)
    )
    private void impetus$captureProjection(float fovDegrees, float aspect, float near, float far,
                                           Operation<Void> original) {
        // m11 of a perspective matrix is cot(fovY/2), m00 is that over the aspect ratio; Panini wants
        // their reciprocals, so hand over the matrix entries and let it invert them.
        float cotHalfFov = (float) (1.0D / Math.tan(Math.toRadians(fovDegrees) / 2.0D));
        PaniniProjection.captureProjection(cotHalfFov / aspect, cotHalfFov);

        original.call(fovDegrees, aspect, near, far);
    }

    @Inject(
            method = "updateCameraAndRender",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;renderEntityOutlineFramebuffer()V",
                    shift = At.Shift.AFTER)
    )
    private void impetus$applyPanini(float partialTicks, long nanoTime, CallbackInfo ci) {
        PaniniProjection.render(partialTicks);
    }
}
