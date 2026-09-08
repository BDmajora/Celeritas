package com.bdmajora.impetus.umbra.gl.blending;

import com.bdmajora.impetus.lwjgl.GL11;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Keeps shader-pack blend-off overrides stable while vanilla fixed-function renderers draw into packed gbuffer targets.
 * Only blend enable is suppressed; replaying deferred blend state later breaks hand/entity transitions.
 */
public final class BlendOverrideGuard {
    private static boolean capturing;
    private static boolean locked;
    private static boolean lockedBlendOff;

    private BlendOverrideGuard() {
    }

    public static void beforeProgramBlendApply(boolean active) {
        release();
        capturing = active;
    }

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

    public static boolean recordEnableBlend() {
        return shouldSuppressBlendEnable();
    }

    private static boolean shouldSuppressBlendEnable() {
        return locked && lockedBlendOff;
    }
}
