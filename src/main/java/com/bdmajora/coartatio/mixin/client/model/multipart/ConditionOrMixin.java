package com.bdmajora.coartatio.mixin.client.model.multipart;

import com.bdmajora.coartatio.state.ConditionCanonicalizer;
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

/**
 * The {@code OR} counterpart to {@link ConditionAndMixin}.
 *
 * <p>{@code OR} branches cannot be flattened into property/value arrays the way {@code AND} branches
 * can — their children are arbitrary sub-trees — so this only replaces the Guava composite with an
 * array-backed one and interns the result. Less dramatic, but it still drops the retained
 * {@code Iterable} and lets identical {@code OR} groups share one instance.
 */
@Mixin(ConditionOr.class)
public class ConditionOrMixin {
    @Shadow
    @Final
    Iterable<ICondition> conditions;

    @Inject(method = "getPredicate", at = @At("HEAD"), cancellable = true)
    private void coartatio$flatten(BlockStateContainer container,
                                   CallbackInfoReturnable<Predicate<IBlockState>> cir) {
        cir.setReturnValue(ConditionCanonicalizer.any(
                ConditionCanonicalizer.resolve(this.conditions, container)));
    }
}
