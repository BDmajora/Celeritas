package com.bdmajora.coartatio.mixin.client.model.multipart;

import com.bdmajora.coartatio.state.ConditionCanonicalizer;
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

// Replaces ConditionPropertyValue.getPredicate's leaf predicate with a flattened, interned one:
// vanilla allocates a fresh anonymous Predicate (plus an OR composite for multi-valued conditions
// like facing=north|south) per call, and none of those closures can ever compare equal.
// Cancels at HEAD instead of @Overwrite so other mods' injections into this method still apply.
@Mixin(ConditionPropertyValue.class)
public class ConditionPropertyValueMixin {
    @Shadow
    @Final
    private String key;

    @Shadow
    @Final
    private String value;

    @Inject(method = "getPredicate", at = @At("HEAD"), cancellable = true)
    private void coartatio$canonicalize(BlockStateContainer container,
                                        CallbackInfoReturnable<Predicate<IBlockState>> cir) {
        cir.setReturnValue(ConditionCanonicalizer.propertyValue(container, this.key, this.value));
    }
}
