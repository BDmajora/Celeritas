package com.bdmajora.coarctatio.mixin.client.model;

import com.bdmajora.coarctatio.collections.CollectionHelper;
import net.minecraft.client.renderer.block.model.WeightedBakedModel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// Compacts WeightedBakedModel's list, tiny per model but one per variant; only read by WeightedRandom.getRandomItem's indexed access, which FixedArrayList serves at the same cost
@Mixin(WeightedBakedModel.class)
public class WeightedBakedModelMixin {
    // Made mutable so the constructor injection can swap it after vanilla assigns it
    @Mutable
    @Shadow
    @Final
    private List<WeightedBakedModel.WeightedModel> models;

    // At RETURN the list is fully populated and never written again
    @Inject(method = "<init>", at = @At("RETURN"))
    private void coarctatio$compactModelList(CallbackInfo ci) {
        this.models = CollectionHelper.fixed(this.models);
    }
}
