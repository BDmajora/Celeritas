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

/**
 * Replaces the model graph's hash maps with fastutil equivalents.
 *
 * <p>Hydrogen's {@code MixinModelLoader}, retargeted: on 1.12.2 the collections live on
 * {@code ModelBakery} rather than 1.16's {@code ModelLoader}, and there are more of them.
 *
 * <p>These are big. On a measured pack, {@code variants} alone holds 21k entries keyed by
 * {@code ModelResourceLocation}, and {@code models}/{@code blockDefinitions} are keyed by the
 * {@code ResourceLocation}s that make up the 109k-instance population Coartatio already interns.
 * A {@code HashMap.Node} is 32 bytes of key/value/hash/next per entry plus a table slot;
 * {@code Object2ObjectOpenHashMap} is open-addressed, so an entry is two array slots and nothing
 * else.
 *
 * <p>Ordering matters for two of them. {@code models}, {@code variants},
 * {@code multipartVariantMap} and {@code itemLocations} are {@code LinkedHashMap}s in vanilla, and
 * bake order affects which model wins a collision — so those keep insertion order by being left
 * alone. Only the genuinely unordered maps are swapped, which is the difference between a memory
 * optimisation and a rendering bug.
 *
 * <p>{@code variantNames} is an {@code IdentityHashMap} keyed by {@code Item}; the fastutil
 * equivalent is {@code Reference2ObjectOpenHashMap}, which keeps identity semantics.
 */
@Mixin(ModelBakery.class)
public abstract class ModelBakeryMixin implements BakeStateReleasable {
    @Shadow
    @Final
    private Map<ModelBlockDefinition, Collection<ModelResourceLocation>> multipartVariantMap;

    /**
     * Cleared from {@code ModelLoaderCleanupMixin} once baking is finished. It lives here rather
     * than there because the field is private to this class — see {@link BakeStateReleasable}.
     */
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
