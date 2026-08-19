package com.bdmajora.coartatio.mixin.client.model;

import com.bdmajora.coartatio.collections.CollectionHelper;
import com.bdmajora.coartatio.dedup.TransformCaches;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.block.model.SimpleBakedModel;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Compacts the quad lists of the most common baked model type.
 *
 * <p>{@code SimpleBakedModel} is built by a {@code Builder} using {@code Lists.newArrayList()}, which
 * leaves each of the seven lists (six faces plus general) with a growth buffer that is on average a
 * third empty, plus the {@code ArrayList} object itself. Multiplied by every model in the game, the
 * slack is worth more than it looks.
 *
 * <p>{@code faceQuads} is rebuilt as an {@code EnumMap} rather than edited in place. Vanilla's
 * {@code Builder} hands over a mutable {@code EnumMap}, but {@code SimpleBakedModel} is public and
 * plenty of mods construct one directly with {@code ImmutableMap.of(...)} — and
 * {@code Entry.setValue} on an immutable map throws. Rebuilding is also a small win in that case,
 * since an {@code EnumMap} over {@code EnumFacing} is a six-slot array against
 * {@code ImmutableMap}'s hash table.
 *
 * <p>Absent faces stay absent: {@code getQuads} returns whatever the map holds for a side, so
 * copying only the present keys preserves a null return exactly where the model already had one.
 *
 * <p>The injection targets every constructor rather than a fixed descriptor so it survives Forge or
 * a coremod adding a parameter. {@link CollectionHelper#fixed} is idempotent, so a delegating
 * constructor chain compacting twice is harmless.
 */
@Mixin(SimpleBakedModel.class)
public class SimpleBakedModelMixin {
    @Mutable
    @Shadow
    @Final
    protected List<BakedQuad> generalQuads;

    @Mutable
    @Shadow
    @Final
    protected Map<EnumFacing, List<BakedQuad>> faceQuads;

    @Mutable
    @Shadow
    @Final
    protected ItemCameraTransforms cameraTransforms;

    @Mutable
    @Shadow
    @Final
    protected ItemOverrideList itemOverrideList;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void coartatio$compactQuadLists(CallbackInfo ci) {
        // Every model carries these two and almost none of them differ — see TransformCaches.
        this.cameraTransforms = TransformCaches.TRANSFORMS.deduplicate(this.cameraTransforms);
        this.itemOverrideList = TransformCaches.deduplicate(this.itemOverrideList);

        this.generalQuads = CollectionHelper.fixed(this.generalQuads);

        Map<EnumFacing, List<BakedQuad>> compacted = new EnumMap<>(EnumFacing.class);

        for (Map.Entry<EnumFacing, List<BakedQuad>> entry : this.faceQuads.entrySet()) {
            compacted.put(entry.getKey(), CollectionHelper.fixed(entry.getValue()));
        }

        this.faceQuads = compacted;
    }
}
