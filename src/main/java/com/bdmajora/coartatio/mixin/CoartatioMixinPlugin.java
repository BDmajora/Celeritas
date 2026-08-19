package com.bdmajora.coartatio.mixin;

import com.bdmajora.coartatio.Coartatio;
import com.bdmajora.coartatio.CoartatioConfig;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates each Coartatio mixin on its config switch.
 *
 * <p>Note the difference from {@code ImpetusVintageMixinPlugin}: that plugin discovers its mixins by
 * scanning the package and returning them from {@code getMixins()}, and Mixin deliberately skips
 * {@code shouldApplyMixin} for plugin-supplied mixins — so those cannot be vetoed. Coartatio
 * declares its mixins in {@code mixins.coartatio.json} instead, which routes them through this
 * method and makes "off" mean "never loaded" rather than "loaded and inert".
 *
 * <p>That distinction matters for a memory mod: a disabled feature should cost nothing, and a
 * feature suspected of causing a crash should be removable without a rebuild.
 */
public class CoartatioMixinPlugin implements IMixinConfigPlugin {
    private static final String PACKAGE = "com.bdmajora.coartatio.mixin.";

    private CoartatioConfig config;

    @Override
    public void onLoad(String mixinPackage) {
        this.config = CoartatioConfig.get();
        Coartatio.LOGGER.info("Coartatio memory subsystem loading");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

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
            case "client.model.CoartatioBakedQuadMixin":
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
            case "client.ModelManagerMixin":
                // Drives the pool lifecycle and the statistics dump; pointless with nothing pooling.
                return this.config.poolQuadVertexData || this.config.canonicalizeMultipartConditions;
            default:
                Coartatio.LOGGER.warn("No config switch is wired up for {}, applying it", mixinClassName);
                return true;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
