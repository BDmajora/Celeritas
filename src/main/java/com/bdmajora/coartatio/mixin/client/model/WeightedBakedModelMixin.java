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

/**
 * Compacts the model list of a weighted (random-variant) model.
 *
 * <p>Small on its own — these lists hold two to four entries — but weighted models are per-variant,
 * so packs with randomised grass, stone or ore textures create a great many of them, and an
 * {@code ArrayList} holding three elements is mostly overhead.
 *
 * <p>Note the list is only ever read through {@code WeightedRandom.getRandomItem}, which indexes it;
 * {@link com.bdmajora.coartatio.collections.FixedArrayList} indexes in the same O(1).
 */
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
