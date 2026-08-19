package com.bdmajora.coartatio.mixin.client.model;

import com.bdmajora.coartatio.dedup.ModelCaches;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pools {@code BakedQuad.vertexData}, the largest single allocation in the baked model graph.
 *
 * <p>Every quad carries a 28-int array — 112 bytes plus header — and identical geometry produces
 * byte-identical arrays. Every plain cube face with the same texture, in the same orientation, at the
 * same tint, bakes to the same 28 ints. Across a large pack this is millions of arrays collapsing to
 * tens of thousands. Combines Hydrogen's {@code MixinBakedQuad} with LoliASM's
 * {@code LoliVertexDataPool}.
 *
 * <p><b>The class check is not optional.</b> Two subclasses in the 1.12.2 tree write to
 * {@code vertexData} <i>after</i> {@code super(...)} has returned:
 *
 * <ul>
 *   <li>{@code BakedQuadRetextured} clones its source array, calls {@code super}, then rewrites the
 *       UV components in place.
 *   <li>{@code UnpackedBakedQuad} passes a zero-filled array to {@code super} and packs its real
 *       vertex data into it lazily on the first {@code getVertexData()}.
 * </ul>
 *
 * <p>Pooling either one hands the same array to unrelated quads and then lets one of them rewrite it
 * — every quad sharing it renders as garbage, and the failure appears far from its cause. Restricting
 * to {@code getClass() == BakedQuad.class} gives up dedup for well-behaved third-party subclasses,
 * which is a trade worth making: those are a small minority of quads, and there is no way to tell
 * them apart from the dangerous ones.
 *
 * <p>The pool is opened and closed around the model bake, so quads created later (dynamic models,
 * runtime re-bakes) are never pooled — which is also where mutation is most likely.
 *
 * <p>Impetus' own {@code BakedQuadMixin} caches flags and normals derived from this array. Those
 * caches stay correct precisely because pooled arrays are guaranteed immutable by the rule above.
 */
@Mixin(BakedQuad.class)
public class CoartatioBakedQuadMixin {
    @Mutable
    @Shadow
    @Final
    protected int[] vertexData;

    /**
     * Pinned to the six-argument constructor by full descriptor, deliberately.
     *
     * <p>Forge gives {@code BakedQuad} two constructors: the original four-argument one, now
     * {@code @Deprecated} and reduced to {@code this(..., true, ITEM)}, and the six-argument one that
     * takes the vertex format. Forge repoints every vanilla call site — including
     * {@code FaceBakery.makeBakedQuad} — at the six-argument form.
     *
     * <p>A bare {@code method = "<init>"} binds to the first constructor in the class file, which is
     * the deprecated one. That satisfies {@code defaultRequire: 1} without error and then never
     * fires, because nothing calls it: the first build of this mixin pooled exactly zero arrays
     * across a full model bake. Naming the descriptor removes the ambiguity, and the four-argument
     * form is still covered because it delegates here.
     */
    @Inject(
            method = "<init>([IILnet/minecraft/util/EnumFacing;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;ZLnet/minecraft/client/renderer/vertex/VertexFormat;)V",
            at = @At("RETURN")
    )
    private void coartatio$poolVertexData(CallbackInfo ci) {
        if (((Object) this).getClass() != BakedQuad.class) {
            ModelCaches.recordSkippedQuad(((Object) this).getClass());
            return;
        }

        this.vertexData = ModelCaches.QUADS.deduplicate(this.vertexData);
    }
}
