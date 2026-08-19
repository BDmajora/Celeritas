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

/**
 * Interns the {@code variant} of every {@code ModelResourceLocation}.
 *
 * <p>Hydrogen splits the variant on commas and interns each property fragment separately. That is
 * the right call on 1.16, where variant strings are long; on 1.12.2 the distribution is different —
 * {@code "normal"} and {@code "inventory"} dominate outright, and the remainder are short. Interning
 * the whole string gets most of the benefit for one pool lookup and no per-instance {@code String[]}
 * header. Splitting is on the roadmap as a measured experiment (§3, item 3.5) rather than an
 * assumption.
 */
@Mixin(ModelResourceLocation.class)
public class ModelResourceLocationMixin {
    @Mutable
    @Shadow
    @Final
    private String variant;

    @Inject(method = "<init>(I[Ljava/lang/String;)V", at = @At("RETURN"))
    private void coartatio$internVariant(int unused, String[] parts, CallbackInfo ci) {
        this.variant = ModelCaches.VARIANTS.deduplicate(this.variant);
    }
}
