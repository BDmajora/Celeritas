package com.bdmajora.extras.mixin.prevent_shaders;

import com.bdmajora.extras.Extras;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Stops vanilla's own post-processing chain from ever loading
// This is the spectator/creeper/entity-view effect chain, NOT the shader-pack pipeline: it is easy to switch on
// by accident (F4 while spectating) and it fights Impetus' framebuffer handling. Nothing here touches Umbra
// The 1.12.2 method names are loadShader and switchUseShader; the 1.20 equivalents, for anyone porting this,
// are GameRenderer.loadPostProcessor and togglePostProcessorEnabled
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

    // The F4 cycle path. Blocked separately because it picks the next chain itself instead of going through
    // loadShader with a name, so cancelling loadShader alone would still let it advance its internal index
    @Inject(method = "switchUseShader", at = @At("HEAD"), cancellable = true)
    private void impetus$preventSwitchUseShader(CallbackInfo ci) {
        if (Extras.options().render.preventShaders) {
            ci.cancel();
        }
    }
}
