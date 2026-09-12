package com.bdmajora.coarctatio.mixin.client.model.multipart;

import com.bdmajora.coarctatio.state.ConditionCanonicalizer;
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

// Vanilla's Predicates.and chain retains the ICondition list and BlockStateContainer for the model's lifetime; when every child is a plain property=value test the tree collapses to one AllMatchOne over two arrays
@Mixin(ConditionAnd.class)
public class ConditionAndMixin {
    // Package-private to match the target's visibility, which mappings render inconsistently
    @Shadow
    @Final
    Iterable<ICondition> conditions;

    // Cancelled at HEAD so the Guava chain is never built
    @Inject(method = "getPredicate", at = @At("HEAD"), cancellable = true)
    private void coarctatio$flatten(BlockStateContainer container,
                                   CallbackInfoReturnable<Predicate<IBlockState>> cir) {
        cir.setReturnValue(ConditionCanonicalizer.all(
                ConditionCanonicalizer.resolve(this.conditions, container)));
    }
}
