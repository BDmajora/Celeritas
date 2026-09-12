package com.bdmajora.coarctatio.mixin.util;

import com.bdmajora.coarctatio.dedup.ModelCaches;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Interns every ModelResourceLocation's variant string whole rather than per-fragment like Hydrogen, since "normal" and "inventory" dominate on 1.12.2
@Mixin(ModelResourceLocation.class)
public class ModelResourceLocationMixin {
    // Made mutable so it can be swapped for the pooled instance after vanilla assigns it
    @Mutable
    @Shadow
    @Final
    private String variant;

    // The varargs constructor every public one funnels through, so one hook covers all; int is vanilla's dummy, and parts is unused since the field is already assigned at RETURN
    @Inject(method = "<init>(I[Ljava/lang/String;)V", at = @At("RETURN"))
    private void coarctatio$internVariant(int unused, String[] parts, CallbackInfo ci) {
        this.variant = ModelCaches.VARIANTS.deduplicate(this.variant);
    }
}
