package com.bdmajora.coarctatio.mixin;

import com.bdmajora.coarctatio.Coarctatio;
import com.bdmajora.coarctatio.CoarctatioConfig;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

// Gates each Coarctatio mixin on its config switch; off means never loaded, so a suspect feature can be disabled without a rebuild
public class CoarctatioMixinPlugin implements IMixinConfigPlugin {
    private static final String PACKAGE = "com.bdmajora.coarctatio.mixin.";

    private CoarctatioConfig config;

    // Reads the config once; it is a Properties file precisely so this is safe during coremod setup
    @Override
    public void onLoad(String mixinPackage) {
        this.config = CoarctatioConfig.get();
    }

    // Impetus reobfuscates mixins directly, so there is no refmap to name
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    // Maps the mixin's simple name to its switch; anything unlisted is refused
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String name = mixinClassName.startsWith(PACKAGE)
                ? mixinClassName.substring(PACKAGE.length())
                : mixinClassName;

        switch (name) {
            case "util.ResourceLocationMixin":
                return this.config.deduplicateResourceLocations;
            case "util.ModelResourceLocationMixin":
                return this.config.deduplicateModelVariants;
            case "nbt.NBTTagCompoundMixin":
                return this.config.compactNbtBackingMap;
            case "client.model.CoarctatioBakedQuadMixin":
                return this.config.poolQuadVertexData;
            case "client.model.SimpleBakedModelMixin":
            case "client.model.WeightedBakedModelMixin":
            case "client.model.MultipartBakedModelMixin":
                return this.config.compactBakedModels;
            case "client.model.multipart.ConditionAndMixin":
            case "client.model.multipart.ConditionOrMixin":
            case "client.model.multipart.ConditionPropertyValueMixin":
                return this.config.canonicalizeMultipartConditions;
            case "state.BlockStateContainerMixin":
            case "state.ExtendedBlockStateMixin":
                return this.config.optimizeBlockStates;
            case "client.model.ModelBakeryMixin":
            case "client.model.ModelLoaderMixin":
                return this.config.compactModelGraph;
            case "world.AnvilChunkLoaderMixin":
                return this.config.stripChunkNbt;
            case "world.ChunkMixin":
                return this.config.dropEmptyChunkSections;
            case "client.texture.TextureMapMixin":
                return this.config.releaseSpriteData;
            case "client.model.ModelLoaderCleanupMixin":
                return this.config.releaseBakeState;
            case "client.SearchTreeMixin":
                return this.config.lazySearchTrees;
            case "core.LockCodeMixin":
            case "client.SoundRegistryMixin":
            case "core.ObjectHolderRegistryMixin":
            case "core.RegistrySimpleMixin":
            case "core.EntityDataManagerMixin":
            case "core.ClassInheritanceMultiMapMixin":
                return this.config.compactRuntimeCollections;
            case "client.ModelManagerMixin":
                // Drives the pool lifecycle and the statistics dump; pointless with nothing pooling.
                return this.config.poolQuadVertexData || this.config.canonicalizeMultipartConditions;
            default:
                Coarctatio.LOGGER.warn("No config switch is wired up for {}, applying it", mixinClassName);
                return true;
        }
    }

    // Nothing to negotiate with other configs
    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    // Null means use the mixin list from the json
    @Override
    public List<String> getMixins() {
        return null;
    }

    // No pre-apply rewriting needed
    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    // No post-apply rewriting needed
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
