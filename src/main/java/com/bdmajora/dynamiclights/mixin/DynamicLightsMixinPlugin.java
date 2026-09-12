package com.bdmajora.dynamiclights.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

// Exists to announce the subsystem; it gates nothing
// Same reasoning as ExtrasMixinPlugin: every mixin here reads the mode switch at CALL time, so gating them at
// coremod load would trade a live, user-changeable switch for one predictable-branch read
// Turning dynamic lights off therefore leaves the mixins applied and inert rather than unapplied
public class DynamicLightsMixinPlugin implements IMixinConfigPlugin {
    // Nothing to prepare; the interface requires the method
    @Override
    public void onLoad(String mixinPackage) {
    }

    // Impetus reobfuscates mixins directly, so there is no refmap to name
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    // Always true; every mixin reads the mode switch at call time
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
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

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
    }
}
