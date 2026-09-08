package com.bdmajora.coartatio.mixin.client.model;

import com.bdmajora.coartatio.collections.ArrayBackedLinkedMap;
import com.google.common.base.Predicate;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.MultipartBakedModel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

// Replaces MultipartBakedModel's LinkedHashMap selector map with an array-backed map: only ever
// iterated (never looked up by key), and multipart blockstates (fences, walls, panes, wires...)
// are numerous enough that the per-entry LinkedHashMap.Entry overhead adds up.
// Insertion order must be preserved: selectors apply in declaration order, visible in quad output.
@Mixin(MultipartBakedModel.class)
public class MultipartBakedModelMixin {
    @Mutable
    @Shadow
    @Final
    private Map<Predicate<IBlockState>, IBakedModel> selectors;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void coartatio$compactSelectors(CallbackInfo ci) {
        if (this.selectors instanceof ArrayBackedLinkedMap) {
            return;
        }

        this.selectors = new ArrayBackedLinkedMap<>(this.selectors);
    }
}
