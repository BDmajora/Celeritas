package com.bdmajora.fulgor.api;

// Implemented by Block; caches whether its light values vary with position (Phosphor's BlockStateLightInfo), since Forge's defaults re-derive from state and an un-overridden block can use the cached value. Flags resolved by reflection on first use
public interface LightInfoBlock {
    // True when this block's class overrides the position-aware getLightValue
    boolean fulgor$hasPositionAwareLightValue();

    // True when this block's class overrides the position-aware getLightOpacity
    boolean fulgor$hasPositionAwareOpacity();
}
