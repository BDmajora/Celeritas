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

/**
 * Flattens {@code AND} conditions into a single array-backed predicate.
 *
 * <p>This is where the bulk of the saving is. Vanilla returns
 * {@code Predicates.and(Iterables.transform(conditions, ...))}, which is: the composite, the
 * transforming {@code Iterable}, the {@code Function}, and one anonymous predicate per child — and
 * the transformed {@code Iterable} holds the original {@code ICondition} list and its
 * {@code BlockStateContainer} alive for as long as the baked model exists.
 *
 * <p>When every child is a plain {@code property=value} test — which is nearly always, since that is
 * what a multipart {@code "when"} block with several keys compiles to — the whole tree collapses to
 * one {@link com.bdmajora.coartatio.state.predicate.AllMatchOne} holding two arrays.
 *
 * <p>The {@code conditions} field is declared package-private here so the shadow is valid whether
 * the target field is private (vanilla) or package-private (as some mappings render it): Mixin
 * requires a shadow to be at least as visible as its target, never less.
 */
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
