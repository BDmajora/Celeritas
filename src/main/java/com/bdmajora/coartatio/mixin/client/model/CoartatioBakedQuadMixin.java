package com.bdmajora.coartatio.mixin.client.model;

import com.bdmajora.coartatio.dedup.ModelCaches;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Dedups BakedQuad.vertexData: identical geometry (same texture/orientation/tint) bakes to
// byte-identical 28-int arrays, so pooling collapses millions down to tens of thousands.
// Restricted to exact BakedQuad instances because BakedQuadRetextured and UnpackedBakedQuad
// mutate vertexData after construction, which would corrupt shared pooled arrays.
// Only active during the model bake; quads baked later are never pooled.
@Mixin(BakedQuad.class)
public class CoartatioBakedQuadMixin {
    @Mutable
    @Shadow
    @Final
    protected int[] vertexData;

    // Pinned to the six-arg ctor by full descriptor: a bare "<init>" binds to the deprecated
    // four-arg ctor (which delegates here anyway), and silently never fires since nothing calls it.
    @Inject(
            method = "<init>([IILnet/minecraft/util/EnumFacing;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;ZLnet/minecraft/client/renderer/vertex/VertexFormat;)V",
            at = @At("RETURN")
    )
    private void coartatio$poolVertexData(CallbackInfo ci) {
        if (((Object) this).getClass() != BakedQuad.class) {
            ModelCaches.recordSkippedQuad(((Object) this).getClass());
            return;
        }

        this.vertexData = ModelCaches.QUADS.deduplicate(this.vertexData);
    }
}
