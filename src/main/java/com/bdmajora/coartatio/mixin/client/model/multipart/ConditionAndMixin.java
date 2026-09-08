package com.bdmajora.coartatio.mixin.client.model.multipart;

import com.bdmajora.coartatio.state.ConditionCanonicalizer;
import com.google.common.base.Predicate;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.multipart.ConditionAnd;
import net.minecraft.client.renderer.block.model.multipart.ICondition;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Replaces ConditionAnd.getPredicate: vanilla allocates a Predicates.and/Iterables.transform chain
// that keeps the original ICondition list and BlockStateContainer alive for the model's lifetime.
// When every child is a plain property=value test (the common case), the whole tree collapses to
// one AllMatchOne holding two arrays instead.
// conditions is shadowed package-private since vanilla/mappings render its visibility differently,
// and a Mixin shadow must be at least as visible as its target.
@Mixin(ConditionAnd.class)
public class ConditionAndMixin {
    @Shadow
    @Final
    Iterable<ICondition> conditions;

    @Inject(method = "getPredicate", at = @At("HEAD"), cancellable = true)
    private void coartatio$flatten(BlockStateContainer container,
                                   CallbackInfoReturnable<Predicate<IBlockState>> cir) {
        cir.setReturnValue(ConditionCanonicalizer.all(
                ConditionCanonicalizer.resolve(this.conditions, container)));
    }
}
