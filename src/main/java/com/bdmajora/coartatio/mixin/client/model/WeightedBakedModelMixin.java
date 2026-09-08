package com.bdmajora.coartatio.mixin.client.model;

import com.bdmajora.coartatio.collections.CollectionHelper;
import net.minecraft.client.renderer.block.model.WeightedBakedModel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// Compacts WeightedBakedModel's model list. Each list is tiny (2-4 entries) but weighted models
// are per-variant, so randomised grass/stone/ore textures create many of them; only ever read via
// WeightedRandom.getRandomItem's indexed access, which FixedArrayList serves at the same O(1).
@Mixin(WeightedBakedModel.class)
public class WeightedBakedModelMixin {
    @Mutable
    @Shadow
    @Final
    private List<WeightedBakedModel.WeightedModel> models;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void coartatio$compactModelList(CallbackInfo ci) {
        this.models = CollectionHelper.fixed(this.models);
    }
}
