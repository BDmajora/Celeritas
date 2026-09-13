package com.bdmajora.extras.mixin.booster;

import com.bdmajora.extras.client.booster.StreamingUploader;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Routes immediate-mode draws through the streamed vertex buffer when GPU Booster has it on; a false from the uploader leaves vanilla's client-array path to run as before
@Mixin(WorldVertexBufferUploader.class)
public class WorldVertexBufferUploaderMixin {
    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void impetus$streamDraw(BufferBuilder builder, CallbackInfo ci) {
        if (StreamingUploader.draw(builder)) {
            ci.cancel();
        }
    }
}
