package com.bdmajora.impetus.booter;

import java.util.List;

// Early mixins are defined as mixins that affects vanilla or forge classes. Or technically, classes that can be
// queried via the current state of net.minecraft.launchwrapper.LaunchClassLoader Implement this in your
// net.minecraftforge.fml.relauncher.IFMLLoadingPlugin. Return all early mixin configs you want MixinBooter to
// queue and send to Mixin library
@Deprecated
public interface IEarlyMixinLoader {

    List<String> getMixinConfigs();

    // Runs when a mixin config is successfully queued and sent to Mixin library
    default boolean shouldMixinConfigQueue(Context context) {
        return this.shouldMixinConfigQueue(context.mixinConfig());
    }

    // Runs when a mixin config is successfully queued and sent to Mixin library
    default boolean shouldMixinConfigQueue(String mixinConfig) {
        return true;
    }

    // Runs when a mixin config is successfully queued and sent to Mixin library
    default void onMixinConfigQueued(Context context) {
        this.onMixinConfigQueued(context.mixinConfig());
    }

    // Runs when a mixin config is successfully queued and sent to Mixin library
    default void onMixinConfigQueued(String mixinConfig) { }

}
