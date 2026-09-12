package com.bdmajora.impetus.umbra.uniforms.custom;

import com.bdmajora.impetus.umbra.gl.uniform.UniformCollector;

// The custom-uniform set of whichever pack is loaded; static like CommonUniforms since threading the pipeline through every compilation path (several reached from mixins with no reference) is worse. Compile sites call assignTo beside addCommonUniforms, the pipeline calls update once per frame before any upload
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

    // This frame's evaluated value for every pack-declared uniform and variable, or empty with no pack; formatted as strings for display, a reporting view only
    public static java.util.Map<String, String> snapshot() {
        CustomUniforms current = active;
        return current == null ? java.util.Collections.emptyMap() : current.snapshot();
    }
}
