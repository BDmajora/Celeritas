package com.bdmajora.extras.mixin.prevent_shaders;

import com.bdmajora.extras.Extras;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks the vanilla post-processing shader pipeline.
 *
 * <p>Not the shader-pack pipeline — this is the spectator/creeper/entity-view effect chain, which is
 * both easy to activate by accident (F4 in spectator mode) and awkward next to Impetus' own
 * framebuffer handling. Nothing here touches Iris.
 *
 * <p>1.20's equivalents are {@code GameRenderer.loadPostProcessor} and
 * {@code togglePostProcessorEnabled}; on 1.12.2 they are {@code loadShader} and
 * {@code switchUseShader}.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererPreventShadersMixin {
    @Inject(method = "loadShader", at = @At("HEAD"), cancellable = true)
    private void impetus$preventLoadShader(ResourceLocation shader, CallbackInfo ci) {
        if (Extras.options().render.preventShaders) {
            ci.cancel();
        }
    }

    @Inject(method = "switchUseShader", at = @At("HEAD"), cancellable = true)
    private void impetus$preventSwitchUseShader(CallbackInfo ci) {
        if (Extras.options().render.preventShaders) {
            ci.cancel();
        }
    }
}
