package com.bdmajora.coarctatio.mixin.client.model;

import com.bdmajora.coarctatio.collections.ArrayBackedLinkedMap;
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

// Selectors are only iterated, never keyed, so the LinkedHashMap.Entry per selector is pure overhead; insertion order must survive since selectors apply in declaration order
@Mixin(MultipartBakedModel.class)
public class MultipartBakedModelMixin {
    // Predicate keys come from the canonicalizer, so identical conditions are already shared
    @Mutable
    @Shadow
    @Final
    private Map<Predicate<IBlockState>, IBakedModel> selectors;

    // Guarded because a mod re-baking a model in place would otherwise copy an already-compacted map
    @Inject(method = "<init>", at = @At("RETURN"))
    private void coarctatio$compactSelectors(CallbackInfo ci) {
        if (this.selectors instanceof ArrayBackedLinkedMap) {
            return;
        }

        this.selectors = new ArrayBackedLinkedMap<>(this.selectors);
    }
}
