package com.bdmajora.extras.mixin;

import com.bdmajora.extras.Extras;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

// announces the subsystem; gates nothing
// unlike FulgorMixinPlugin and CoartatioMixinPlugin, this one does *not* gate on the Extras config
// every Extras mixin reads its switch at call time, which is what makes the switches live rather than
// needing a restart, so gating them here would trade that away for nothing - a mixin that is applied
// but inert costs a predictable-branch read
// kept as a real plugin rather than dropped from the config because the load-order line in the log is
// worth having next to the other four subsystems, and because this is where a genuine availability
// check would belong if one is ever needed
public class ExtrasMixinPlugin implements IMixinConfigPlugin {
    // Nothing to prepare; the interface requires the method
    @Override
    public void onLoad(String mixinPackage) {
    }

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
