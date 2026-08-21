package com.bdmajora.fulgor.api;

/**
 * Implemented by {@code Block}, caching whether its light values can vary with position.
 *
 * <p>The 1.12.2 equivalent of Phosphor's {@code BlockStateLightInfo}. Forge's default
 * {@code Block.getLightOpacity(state, world, pos)} just forwards to {@code state.getLightOpacity()},
 * and its default {@code getLightValue(state, world, pos)} re-reads the block state out of the world
 * before doing the same — a wasted lookup on every neighbour of every light update, for a block whose
 * answer never depended on the position in the first place.
 *
 * <p>Knowing that a block's class has not overridden either method lets the engine skip straight to
 * the value cached on the block. The flags are derived by reflection on first use, which is well after
 * mod loading has settled, and are per-block rather than per-state because the override is a property
 * of the class.
 */
public interface LightInfoBlock {
    /** True when this block's class overrides the position-aware {@code getLightValue}. */
    boolean fulgor$hasPositionAwareLightValue();

    /** True when this block's class overrides the position-aware {@code getLightOpacity}. */
    boolean fulgor$hasPositionAwareOpacity();
}
