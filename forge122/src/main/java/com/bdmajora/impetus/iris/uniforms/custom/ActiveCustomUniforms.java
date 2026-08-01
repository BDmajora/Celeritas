package com.bdmajora.impetus.iris.uniforms.custom;

import com.bdmajora.impetus.iris.gl.uniform.UniformCollector;

/**
 * The custom-uniform set of the currently active shader pack, if any. Program-compilation sites call
 * {@link #assignTo} alongside {@code CommonUniforms.addCommonUniforms}; the pipeline calls {@link #update}
 * once per frame before program uniforms upload. Mirrors this port's static-registry style (CommonUniforms
 * and friends), avoiding the need to thread the pipeline instance through every compile path.
 */
public final class ActiveCustomUniforms {
    private static CustomUniforms active;

    private ActiveCustomUniforms() {
    }

    public static void set(CustomUniforms uniforms) {
        active = uniforms;
    }

    public static void clear() {
        active = null;
    }

    public static void update() {
        CustomUniforms current = active;
        if (current != null) {
            current.update();
        }
    }

    public static void assignTo(UniformCollector collector) {
        CustomUniforms current = active;
        if (current != null) {
            current.assignTo(collector);
        }
    }
}
