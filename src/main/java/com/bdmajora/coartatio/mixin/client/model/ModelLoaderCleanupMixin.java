package com.bdmajora.coartatio.mixin.client.model;

import com.bdmajora.coartatio.Coartatio;
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

/**
 * Frees the model loader's intermediate state once baking is finished.
 *
 * <p>From FoamFix's {@code ModelLoaderCleanup}, and the natural companion to
 * {@link ModelLoaderMixin}: that one makes these maps smaller, this one lets them go entirely.
 *
 * <p>{@code stateModels} holds an unbaked {@code IModel} per blockstate variant — tens of thousands
 * on a large pack — and every one of them has already been turned into a baked model by the time
 * this runs. {@code loadingExceptions} accumulates a stack trace per model that failed to load,
 * which on a pack with broken models is not small.
 *
 * <p>The hook is {@code onPostBakeEvent} rather than {@code setupModelRegistry}, deliberately:
 * {@code ModelBakeEvent} fires inside it and mods legitimately read {@code stateModels} from that
 * event. Clearing at its return means every consumer has already had its turn. That ordering is the
 * whole reason this is safe, so it should not be moved.
 *
 * <p>Every field here is Forge-added, so each {@code @Shadow} opts out of remapping individually.
 */
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

        Coartatio.LOGGER.info("Released {} unbaked models after bake", models);
    }
}
