package com.bdmajora.extras.mixin;

import com.bdmajora.extras.Extras;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

// Announces the subsystem; gates nothing, since every Extras mixin reads its switch at call time
// Kept as a real plugin so the load-order log line sits beside the other subsystems
public class ExtrasMixinPlugin implements IMixinConfigPlugin {
    // Nothing to prepare; the interface requires the method
    @Override
    public void onLoad(String mixinPackage) {
    }

    // Impetus reobfuscates mixins directly, so there is no refmap to name
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    // applies everything
    // an earlier version gated the Panini mixins on ShaderGroup being loadable, which was wrong twice
    // over: this method runs during mixin config load, before Minecraft's classes are reachable
    // through this class loader, so the probe always failed and silently disabled the option
    // and probing by Class.forName would have force-loaded a Minecraft class ahead of the transformers
    // that need to see it first
    // whether the effect can actually run is a runtime question, and PaniniProjection#shouldApply is
    // where it is asked
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

    // No pre-apply rewriting needed
    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    // No post-apply rewriting needed
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
