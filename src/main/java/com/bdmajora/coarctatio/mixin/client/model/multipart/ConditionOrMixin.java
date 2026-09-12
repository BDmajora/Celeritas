package com.bdmajora.coarctatio.mixin.client.model.multipart;

import com.bdmajora.coarctatio.state.ConditionCanonicalizer;
import com.google.common.base.Predicate;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.multipart.ConditionOr;
import net.minecraft.client.renderer.block.model.multipart.ICondition;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// OR counterpart to ConditionAndMixin; children are arbitrary sub-trees so it cannot flatten, but an interned array-backed composite drops the retained Iterable and shares groups
@Mixin(ConditionOr.class)
public class ConditionOrMixin {
    // Vanilla keeps the child conditions as a lazily evaluated Iterable
    @Shadow
    @Final
    Iterable<ICondition> conditions;

    // Cancelled at HEAD so Guava's Predicates.or chain is never built
    @Inject(method = "getPredicate", at = @At("HEAD"), cancellable = true)
    private void coarctatio$flatten(BlockStateContainer container,
                                   CallbackInfoReturnable<Predicate<IBlockState>> cir) {
        cir.setReturnValue(ConditionCanonicalizer.any(
                ConditionCanonicalizer.resolve(this.conditions, container)));
    }
}
