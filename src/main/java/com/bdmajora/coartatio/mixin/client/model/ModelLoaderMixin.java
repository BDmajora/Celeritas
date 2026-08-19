package com.bdmajora.coartatio.mixin.client.model;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.client.renderer.block.model.ModelBlockDefinition;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.IModel;
import net.minecraftforge.client.model.ModelLoader;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Set;

/**
 * The Forge half of {@link ModelBakeryMixin}.
 *
 * <p>{@code ModelLoader extends ModelBakery} and adds five more collections of its own, of which
 * {@code stateModels} is the largest single map in the model graph — one entry per blockstate
 * variant, 21k of them on a measured pack, each a {@code HashMap.Node}.
 *
 * <p>All five are plain {@code HashMap}/{@code HashSet} in Forge with no ordering contract, so
 * unlike {@code ModelBakery}'s {@code LinkedHashMap}s they can all be swapped safely.
 *
 * <p>{@code remap = false} on the whole mixin: {@code ModelLoader} is a Forge class and none of its
 * members appear in the obfuscation map.
 */
@Mixin(value = ModelLoader.class, remap = false)
public abstract class ModelLoaderMixin {
    @Mutable
    @Shadow
    @Final
    private Map<ModelResourceLocation, IModel> stateModels;

    @Mutable
    @Shadow
    @Final
    private Map<ModelResourceLocation, ModelBlockDefinition> multipartDefinitions;

    @Mutable
    @Shadow
    @Final
    private Map<ModelBlockDefinition, IModel> multipartModels;

    @Mutable
    @Shadow
    @Final
    private Set<ModelResourceLocation> missingVariants;

    @Mutable
    @Shadow
    @Final
    private Map<ResourceLocation, Exception> loadingExceptions;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void coartatio$compactLoaderMaps(CallbackInfo ci) {
        this.stateModels = new Object2ObjectOpenHashMap<>(this.stateModels);
        this.multipartDefinitions = new Object2ObjectOpenHashMap<>(this.multipartDefinitions);
        this.multipartModels = new Object2ObjectOpenHashMap<>(this.multipartModels);
        this.missingVariants = new ObjectOpenHashSet<>(this.missingVariants);
        this.loadingExceptions = new Object2ObjectOpenHashMap<>(this.loadingExceptions);
    }
}
