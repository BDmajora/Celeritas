package com.bdmajora.coartatio.mixin.client.model;

import net.minecraft.client.renderer.block.model.IBakedModel;
import com.bdmajora.coartatio.state.BakeStateReleasable;
import net.minecraft.client.renderer.block.model.ModelBlockDefinition;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.IRegistry;
import net.minecraftforge.client.model.IModel;
import net.minecraftforge.client.model.ModelLoader;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

// Frees ModelLoader's intermediate bake state once baking finishes; companion to ModelLoaderMixin
// (that one shrinks these maps, this one drops them entirely once every entry is baked).
// Hooked at onPostBakeEvent's RETURN rather than setupModelRegistry because ModelBakeEvent fires
// inside it and mods legitimately read stateModels from that event; clearing must happen after.
// All fields here are Forge-added, so each @Shadow opts out of remapping individually.
@Mixin(ModelLoader.class)
public abstract class ModelLoaderCleanupMixin {
    @Shadow(remap = false)
    @Final
    private Map<ModelResourceLocation, IModel> stateModels;

    @Shadow(remap = false)
    @Final
    private Map<ResourceLocation, Exception> loadingExceptions;

    @Shadow(remap = false)
    @Final
    private Map<ModelResourceLocation, ModelBlockDefinition> multipartDefinitions;

    @Shadow(remap = false)
    @Final
    private Map<ModelBlockDefinition, IModel> multipartModels;

    @Inject(method = "onPostBakeEvent", at = @At("RETURN"), remap = false)
    private void coartatio$releaseBakeState(IRegistry<ModelResourceLocation, IBakedModel> registry,
                                            CallbackInfo ci) {
        int models = this.stateModels.size() + this.multipartModels.size();

        this.stateModels.clear();
        this.loadingExceptions.clear();
        this.multipartDefinitions.clear();
        this.multipartModels.clear();
        // multipartVariantMap is private to ModelBakery, so it is cleared from the mixin that owns it.
        models += ((BakeStateReleasable) this).coartatio$releaseBakeryState();

    }
}
