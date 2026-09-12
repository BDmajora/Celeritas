package com.bdmajora.coarctatio.mixin.client.model.multipart;

import com.bdmajora.coarctatio.state.ConditionCanonicalizer;
import com.google.common.base.Predicate;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.multipart.ConditionPropertyValue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Vanilla allocates a fresh anonymous Predicate per call and no two closures compare equal; cancelled at HEAD rather than @Overwrite so other mods' injections still apply
@Mixin(ConditionPropertyValue.class)
public class ConditionPropertyValueMixin {
    // Property name, e.g. facing
    @Shadow
    @Final
    private String key;

    // Expected value, possibly multi-valued like north|south
    @Shadow
    @Final
    private String value;

    // Hands off to the canonicalizer, which interns so identical leaves share one instance
    @Inject(method = "getPredicate", at = @At("HEAD"), cancellable = true)
    private void coarctatio$canonicalize(BlockStateContainer container,
                                        CallbackInfoReturnable<Predicate<IBlockState>> cir) {
        cir.setReturnValue(ConditionCanonicalizer.propertyValue(container, this.key, this.value));
    }
}
