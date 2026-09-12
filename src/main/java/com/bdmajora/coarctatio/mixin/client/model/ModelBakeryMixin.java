package com.bdmajora.coarctatio.mixin.client.model;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.renderer.block.model.ModelBakery;
import com.bdmajora.coarctatio.state.BakeStateReleasable;
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

// Swaps ModelBakery's plain HashMap/HashSet fields for fastutil (no per-entry Node); the LinkedHashMaps where bake order decides collisions are left alone, and variantNames stays identity-keyed via Reference2ObjectOpenHashMap
@Mixin(ModelBakery.class)
public abstract class ModelBakeryMixin implements BakeStateReleasable {
    @Shadow
    @Final
    private Map<ModelBlockDefinition, Collection<ModelResourceLocation>> multipartVariantMap;

    // Cleared from ModelLoaderCleanupMixin once baking finishes; lives here because the field is private to this class (see BakeStateReleasable)
    @Override
    public int coarctatio$releaseBakeryState() {
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
    private void coarctatio$compactModelGraph(CallbackInfo ci) {
        this.sprites = new Object2ObjectOpenHashMap<>(this.sprites);
        this.blockDefinitions = new Object2ObjectOpenHashMap<>(this.blockDefinitions);
        this.variantNames = new Reference2ObjectOpenHashMap<>(this.variantNames);
    }
}
