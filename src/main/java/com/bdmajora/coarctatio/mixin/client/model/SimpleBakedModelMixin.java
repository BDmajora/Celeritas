package com.bdmajora.coarctatio.mixin.client.model;

import com.bdmajora.coarctatio.collections.CollectionHelper;
import com.bdmajora.coarctatio.dedup.TransformCaches;
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

// Compacts SimpleBakedModel's quad lists (vanilla's Lists.newArrayList leaves each an oversized buffer); faceQuads is rebuilt as an EnumMap since some mods pass an ImmutableMap, and every constructor is targeted since CollectionHelper.fixed is idempotent
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
    private void coarctatio$compactQuadLists(CallbackInfo ci) {
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
