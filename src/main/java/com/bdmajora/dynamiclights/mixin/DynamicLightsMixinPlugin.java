package com.bdmajora.dynamiclights.mixin;

import com.bdmajora.dynamiclights.DynamicLights;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Announces the subsystem; gates nothing.
 *
 * <p>Same reasoning as {@code ExtrasMixinPlugin}: every mixin here reads the mode switch at call
 * time, so gating them at coremod load would trade a live switch for a predictable-branch read.
 * Turning dynamic lights off leaves the mixins applied and inert.
 */
public class DynamicLightsMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
        DynamicLights.LOGGER.info("Dynamic Lights subsystem loading");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
    }
}
