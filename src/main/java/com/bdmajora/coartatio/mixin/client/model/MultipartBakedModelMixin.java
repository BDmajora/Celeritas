package com.bdmajora.coartatio.mixin.client.model;

import com.bdmajora.coartatio.collections.ArrayBackedLinkedMap;
import com.google.common.base.Predicate;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.MultipartBakedModel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Replaces the selector map of a multipart model with two flat arrays.
 *
 * <p>Vanilla builds this as a {@code LinkedHashMap} and thereafter only iterates it in
 * {@code getQuads}. A {@code LinkedHashMap.Entry} costs 40 bytes for a hash, a next pointer and two
 * ordering pointers, none of which are ever used; the replacement costs two array slots.
 *
 * <p>Multipart models are one per multipart blockstate, and the block families that use them —
 * fences, walls, panes, wires, and every pipe or cable mod ever written — are exactly the ones a
 * large pack has thousands of.
 *
 * <p>Hydrogen's equivalent replaces a {@code List<Pair<...>>}, because 1.16 changed the field's type;
 * on 1.12.2 it is still a {@code Map}, so this uses an insertion-ordered array map instead of a pair
 * list. Iteration order is preserved, which matters: selectors are applied in declaration order and
 * the resulting quad list order is visible to the renderer.
 */
@Mixin(MultipartBakedModel.class)
public class MultipartBakedModelMixin {
    @Mutable
    @Shadow
    @Final
    private Map<Predicate<IBlockState>, IBakedModel> selectors;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void coartatio$compactSelectors(CallbackInfo ci) {
        if (this.selectors instanceof ArrayBackedLinkedMap) {
            return;
        }

        this.selectors = new ArrayBackedLinkedMap<>(this.selectors);
    }
}
