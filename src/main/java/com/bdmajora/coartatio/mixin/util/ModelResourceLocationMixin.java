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
// Hydrogen splits the variant on commas and interns each property fragment separately, which is right on 1.16
// where variant strings are long. On 1.12.2 the distribution is different: "normal" and "inventory" dominate
// outright and the rest are short, so interning the whole string gets most of the benefit for one pool lookup
// and no per-instance String[] header
@Mixin(ModelResourceLocation.class)
public class ModelResourceLocationMixin {
    @Mutable
    @Shadow
    @Final
    private String variant;

    // The varargs constructor every public constructor funnels through, so one hook covers them all
    // The int parameter is a dummy vanilla adds to disambiguate the overload; parts is unused here because the
    // field has already been assigned by the time this runs at RETURN
    @Inject(method = "<init>(I[Ljava/lang/String;)V", at = @At("RETURN"))
    private void coartatio$internVariant(int unused, String[] parts, CallbackInfo ci) {
        this.variant = ModelCaches.VARIANTS.deduplicate(this.variant);
    }
}
