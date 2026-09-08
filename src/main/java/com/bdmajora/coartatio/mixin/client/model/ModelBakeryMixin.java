package com.bdmajora.coartatio.mixin.client.model;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.renderer.block.model.ModelBakery;
import com.bdmajora.coartatio.state.BakeStateReleasable;
import net.minecraft.client.renderer.block.model.ModelBlockDefinition;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.List;
import java.util.Map;

// Swaps ModelBakery's hash maps for fastutil equivalents to shrink the model graph (open-addressed
// maps avoid the per-entry HashMap.Node overhead). Only swaps maps that are plain HashMap/HashSet in
// vanilla; models/variants/multipartVariantMap/itemLocations are LinkedHashMaps where bake order
// affects which model wins a collision, so those are left alone. variantNames is an IdentityHashMap
// keyed by Item, hence Reference2ObjectOpenHashMap to preserve identity semantics.
@Mixin(ModelBakery.class)
public abstract class ModelBakeryMixin implements BakeStateReleasable {
    @Shadow
    @Final
    private Map<ModelBlockDefinition, Collection<ModelResourceLocation>> multipartVariantMap;

    // Cleared from ModelLoaderCleanupMixin once baking finishes; lives here because the field
    // is private to this class (see BakeStateReleasable).
    @Override
    public int coartatio$releaseBakeryState() {
        int size = this.multipartVariantMap.size();
        this.multipartVariantMap.clear();
        return size;
    }

    @Mutable
    @Shadow
    @Final
    private Map<ResourceLocation, TextureAtlasSprite> sprites;

    @Mutable
    @Shadow
    @Final
    private Map<ResourceLocation, ModelBlockDefinition> blockDefinitions;

    @Mutable
    @Shadow
    @Final
    private Map<Item, List<String>> variantNames;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void coartatio$compactModelGraph(CallbackInfo ci) {
        this.sprites = new Object2ObjectOpenHashMap<>(this.sprites);
        this.blockDefinitions = new Object2ObjectOpenHashMap<>(this.blockDefinitions);
        this.variantNames = new Reference2ObjectOpenHashMap<>(this.variantNames);
    }
}
