package com.bdmajora.impetus.umbra.gl.blending;

import com.bdmajora.impetus.lwjgl.GL11;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// Holds a shader pack's "blend off" override in place while vanilla's fixed-function renderers draw into packed
// gbuffer targets
// The problem it solves: a pack can declare blend.<program>=off, but vanilla renderers call enableBlend() on their
// own throughout the draw, which would switch it back on and blend the gbuffer channels together as if they were
// colours
// Only the blend ENABLE is suppressed. Capturing the full blend state and replaying it later was tried and breaks
// the hand and entity transitions, which legitimately change blend func mid-pass
public final class BlendOverrideGuard {
    // Set between the two hooks below, while the pack's blend state is being applied and the result is not yet known
    private static boolean capturing;
    // True once a pack override has been captured and is being enforced
    private static boolean locked;
    // What was captured: only an override that turned blend OFF is worth enforcing, since an override that left it
    // on agrees with whatever vanilla would have done anyway
    private static boolean lockedBlendOff;

    private BlendOverrideGuard() {
    }

    // Called immediately before a program's blend state is applied. Releases first, so a previous program's lock
    // can never leak into this one
    public static void beforeProgramBlendApply(boolean active) {
        release();
        capturing = active;
    }

    // Called immediately after. Reads real GL rather than trusting what was requested, because the pack's
    // directive goes through the same state manager vanilla uses and may not have taken
    public static void afterProgramBlendApply() {
        if (!capturing) {
            return;
        }
        capturing = false;
        locked = true;
        lockedBlendOff = LWJGL.glGetInteger(GL11.GL_BLEND) == 0;
    }

    public static void release() {
        locked = false;
        capturing = false;
        lockedBlendOff = false;
    }

    // The hook GlStateManager.enableBlend consults; true means "swallow this call"
    public static boolean recordEnableBlend() {
        return shouldSuppressBlendEnable();
    }

    private static boolean shouldSuppressBlendEnable() {
        return locked && lockedBlendOff;
    }
}
