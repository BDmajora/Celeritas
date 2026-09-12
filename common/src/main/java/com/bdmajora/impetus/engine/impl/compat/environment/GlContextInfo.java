package com.bdmajora.impetus.engine.impl.compat.environment;

import com.bdmajora.impetus.lwjgl.LWJGLServiceProvider;

// Immutable snapshot of GL_VENDOR/GL_RENDERER/GL_VERSION; capture() needs a live GL context, the snapshot is then handed to background checks that have none
public record GlContextInfo(String vendor, String renderer, String version) {
    // Literals rather than the generated GL constant classes; these values are fixed by the GL spec
    private static final int GL_VENDOR = 0x1F00;
    private static final int GL_RENDERER = 0x1F01;
    private static final int GL_VERSION = 0x1F02;

    // Reads vendor, renderer and version strings from the current context
    public static GlContextInfo capture() {
        var gl = LWJGLServiceProvider.LWJGL;

        return new GlContextInfo(
                safe(gl.glGetString(GL_VENDOR)),
                safe(gl.glGetString(GL_RENDERER)),
                safe(gl.glGetString(GL_VERSION)));
    }

    // Null to a placeholder, so log lines never NPE
    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
