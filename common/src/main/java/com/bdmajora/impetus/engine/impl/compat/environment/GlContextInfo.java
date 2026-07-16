package com.bdmajora.impetus.engine.impl.compat.environment;

import com.bdmajora.impetus.lwjgl.LWJGLServiceProvider;

/**
 * A snapshot of the {@code GL_VENDOR}/{@code GL_RENDERER}/{@code GL_VERSION} strings of the current context.
 * <p>
 * {@link #capture()} must be called on a thread that owns a live GL context (on legacy Minecraft, the client
 * thread once the display exists). The result is immutable and safe to hand to background threads afterwards.
 */
public record GlContextInfo(String vendor, String renderer, String version) {
    // GL_VENDOR/GL_RENDERER/GL_VERSION are fixed by the GL specification; using the literals here avoids a
    // dependency on the generated GL constant classes.
    private static final int GL_VENDOR = 0x1F00;
    private static final int GL_RENDERER = 0x1F01;
    private static final int GL_VERSION = 0x1F02;

    public static GlContextInfo capture() {
        var gl = LWJGLServiceProvider.LWJGL;

        return new GlContextInfo(
                safe(gl.glGetString(GL_VENDOR)),
                safe(gl.glGetString(GL_RENDERER)),
                safe(gl.glGetString(GL_VERSION)));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
