package com.bdmajora.extras.mixin.prevent_shaders;

import com.bdmajora.extras.Extras;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Stops vanilla's own post-processing chain (the spectator/creeper/entity-view effects, NOT the shader-pack pipeline) from loading, since F4 turns it on by accident and it fights Impetus' framebuffers; 1.20 equivalents are GameRenderer.loadPostProcessor/togglePostProcessorEnabled
@Mixin(EntityRenderer.class)
public class EntityRendererPreventShadersMixin {
    // Cancelled at HEAD so the chain JSON is never parsed and no framebuffers are allocated for it
    @Inject(method = "loadShader", at = @At("HEAD"), cancellable = true)
    private void impetus$preventLoadShader(ResourceLocation shader, CallbackInfo ci) {
        // Read live rather than cached, so turning the option off restores vanilla behaviour without a restart
        if (Extras.options().render.preventShaders) {
            ci.cancel();
        }
    }

    // The F4 cycle path, blocked separately since it picks the next chain itself and cancelling loadShader alone would still advance its index
    @Inject(method = "switchUseShader", at = @At("HEAD"), cancellable = true)
    private void impetus$preventSwitchUseShader(CallbackInfo ci) {
        if (Extras.options().render.preventShaders) {
            ci.cancel();
        }
    }
}
