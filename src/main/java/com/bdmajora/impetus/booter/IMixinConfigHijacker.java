package com.bdmajora.impetus.booter;

import java.util.Set;

// Hijackers are used to stop certain mixin configurations from ever being applied. Usage is similar to
// IEarlyMixinLoader, implement it in your coremod class. Requested by: @Desoroxxx
@Deprecated
public interface IMixinConfigHijacker {

    // Return a set of mixin config names to not be loaded by the mixin environment
    Set<String> getHijackedMixinConfigs();

    // Return a set of mixin config names to not be loaded by the mixin environment
    default Set<String> getHijackedMixinConfigs(Context context) {
        return getHijackedMixinConfigs();
    }

}
