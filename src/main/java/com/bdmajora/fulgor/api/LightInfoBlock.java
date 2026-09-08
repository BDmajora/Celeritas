package com.bdmajora.fulgor.api;

// Implemented by Block; caches whether its light values can vary with position (Phosphor's BlockStateLightInfo, ported).
// Forge's default position-aware getLightValue/getLightOpacity re-derive from state anyway, so if a block's class
// hasn't overridden them the engine can skip straight to the cached value. Flags derived via reflection on first use.
public interface LightInfoBlock {
    // True when this block's class overrides the position-aware getLightValue
    boolean fulgor$hasPositionAwareLightValue();

    // True when this block's class overrides the position-aware getLightOpacity
    boolean fulgor$hasPositionAwareOpacity();
}
