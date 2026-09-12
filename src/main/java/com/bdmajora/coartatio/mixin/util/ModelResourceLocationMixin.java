package com.bdmajora.coartatio.mixin.util;

import com.bdmajora.coartatio.dedup.ModelCaches;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Interns the variant string of every ModelResourceLocation
// Whole-string rather than Hydrogen's per-fragment split: on 1.12.2 "normal" and "inventory" dominate, so one
// pool lookup gets most of the benefit without a per-instance String[] header
@Mixin(ModelResourceLocation.class)
public class ModelResourceLocationMixin {
    // Made mutable so it can be swapped for the pooled instance after vanilla assigns it
    @Mutable
    @Shadow
    @Final
    private String variant;

    // The varargs constructor every public one funnels through, so a single hook covers them all
    // int is vanilla's disambiguating dummy; parts is unused because the field is already assigned at RETURN
    @Inject(method = "<init>(I[Ljava/lang/String;)V", at = @At("RETURN"))
    private void coartatio$internVariant(int unused, String[] parts, CallbackInfo ci) {
        this.variant = ModelCaches.VARIANTS.deduplicate(this.variant);
    }
}
