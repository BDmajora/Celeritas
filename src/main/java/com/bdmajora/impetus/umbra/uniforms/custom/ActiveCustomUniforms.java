package com.bdmajora.impetus.umbra.uniforms.custom;

import com.bdmajora.impetus.umbra.gl.uniform.UniformCollector;

// The custom-uniform set belonging to whichever shader pack is loaded, or nothing when none is
// Static rather than owned by the pipeline, matching how CommonUniforms and the other registries work here. The
// alternative is threading the pipeline instance through every program-compilation path, several of which are
// reached from mixins that have no pipeline reference to hand
// Compile sites call assignTo alongside CommonUniforms.addCommonUniforms; the pipeline calls update once per frame
// before any program uniform uploads, so every program in the frame sees the same evaluated values
public final class ActiveCustomUniforms {
    private static CustomUniforms active;

    private ActiveCustomUniforms() {
    }

    // Installs the pack's custom uniforms
    public static void set(CustomUniforms uniforms) {
        active = uniforms;
    }

    // On pack unload
    public static void clear() {
        active = null;
    }

    // Evaluates every expression for this frame
    public static void update() {
        CustomUniforms current = active;
        if (current != null) {
            current.update();
        }
    }

    // Registers each declared uniform against a program
    public static void assignTo(UniformCollector collector) {
        CustomUniforms current = active;
        if (current != null) {
            current.assignTo(collector);
        }
    }

    // This frame's evaluated value for every pack-declared uniform and variable, or empty when no pack is loaded
    // Formatted as strings for display, so this is a reporting view rather than something the upload path reads
    public static java.util.Map<String, String> snapshot() {
        CustomUniforms current = active;
        return current == null ? java.util.Collections.emptyMap() : current.snapshot();
    }
}
