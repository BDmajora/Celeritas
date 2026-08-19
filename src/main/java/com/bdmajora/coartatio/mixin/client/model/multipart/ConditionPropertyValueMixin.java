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

/**
 * Replaces the leaf of every multipart condition with a flattened, interned predicate.
 *
 * <p>Vanilla's {@code getPredicate} allocates a fresh anonymous {@code Predicate} per call — and for
 * a multi-valued condition ({@code facing=north|south}) also a {@code Predicates.or} composite and a
 * transformed {@code Iterable} over the value list. Every one of those closures captures the
 * property and the parsed value, and none of them can ever compare equal to another, so identical
 * conditions across hundreds of blockstate files each keep their own.
 *
 * <p>Cancelling at {@code HEAD} rather than using {@code @Overwrite} is deliberate: it produces the
 * same result while leaving other mods' injections into this method valid, and it will not fail the
 * mixin apply if another mod also targets it.
 */
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
