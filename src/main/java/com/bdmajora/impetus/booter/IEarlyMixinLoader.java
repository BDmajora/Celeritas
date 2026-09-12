package com.bdmajora.impetus.booter;

import java.util.List;

// Early mixins affect vanilla or Forge classes (anything queryable via the current LaunchClassLoader state); implement in your IFMLLoadingPlugin and return the early mixin configs MixinBooter should queue
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
