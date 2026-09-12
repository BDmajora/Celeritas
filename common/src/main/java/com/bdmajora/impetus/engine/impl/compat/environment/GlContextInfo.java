package com.bdmajora.impetus.engine.impl.compat.environment;

import com.bdmajora.impetus.lwjgl.LWJGLServiceProvider;

// A snapshot of the current context's GL_VENDOR, GL_RENDERER and GL_VERSION strings
// capture() must run on a thread that owns a live GL context — on 1.12.2 that is the client thread, once the
// display exists
// The snapshot is immutable precisely so it can then be handed to the background compatibility checks, which have
// no context of their own and could not query GL themselves
public record GlContextInfo(String vendor, String renderer, String version) {
    // GL_VENDOR/GL_RENDERER/GL_VERSION are fixed by the GL specification; using the literals here avoids a
    // dependency on the generated GL constant classes.
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
