package org.taumc.celeritas.iris.gl.program;

import org.joml.Matrix4fc;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.taumc.celeritas.iris.gl.uniform.FloatSupplier;
import org.taumc.celeritas.iris.gl.uniform.FloatUniform;
import org.taumc.celeritas.iris.gl.uniform.IntUniform;
import org.taumc.celeritas.iris.gl.uniform.MatrixUniform;
import org.taumc.celeritas.iris.gl.uniform.Uniform;
import org.taumc.celeritas.iris.gl.uniform.UniformUpdateFrequency;
import org.taumc.celeritas.iris.gl.uniform.Vector2IntUniform;
import org.taumc.celeritas.iris.gl.uniform.Vector3Uniform;
import org.taumc.celeritas.iris.gl.uniform.Vector4Uniform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The set of uniforms bound to one GL program, plus the per-frame upload driver.
 * <p>
 * Built with {@link Builder}, which resolves each uniform's location at build time and silently drops uniforms the
 * program does not actually declare (OptiFine packs reference far more uniforms than any single program uses).
 * {@link #update()} uploads the {@code ONCE} uniforms on the first frame and everything else every frame; each
 * {@link Uniform} additionally diffs its own value so unchanged uniforms cost nothing on the GL side.
 */
public class ProgramUniforms {
    private final List<Uniform> once;
    private final List<Uniform> perFrame;
    private boolean firstUpdate = true;

    private ProgramUniforms(List<Uniform> once, List<Uniform> perFrame) {
        this.once = once;
        this.perFrame = perFrame;
    }

    public void update() {
        if (this.firstUpdate) {
            this.firstUpdate = false;
            for (int i = 0; i < this.once.size(); i++) {
                this.once.get(i).update();
            }
        }
        for (int i = 0; i < this.perFrame.size(); i++) {
            this.perFrame.get(i).update();
        }
    }

    public static Builder builder(String name, int program) {
        return new Builder(name, program);
    }

    public static class Builder {
        private final String name;
        private final int program;
        private final List<Uniform> once = new ArrayList<>();
        private final List<Uniform> perFrame = new ArrayList<>();

        private Builder(String name, int program) {
            this.name = name;
            this.program = program;
        }

        public String getName() {
            return this.name;
        }

        private int location(CharSequence uniformName) {
            return LWJGL.glGetUniformLocation(this.program, uniformName);
        }

        private void add(UniformUpdateFrequency frequency, Uniform uniform) {
            if (frequency == UniformUpdateFrequency.ONCE) {
                this.once.add(uniform);
            } else {
                this.perFrame.add(uniform);
            }
        }

        public Builder uniform1f(UniformUpdateFrequency frequency, String uniformName, FloatSupplier value) {
            int location = location(uniformName);
            if (location != -1) {
                add(frequency, new FloatUniform(location, value));
            }
            return this;
        }

        public Builder uniform1i(UniformUpdateFrequency frequency, String uniformName, IntSupplier value) {
            int location = location(uniformName);
            if (location != -1) {
                add(frequency, new IntUniform(location, value));
            }
            return this;
        }

        public Builder uniform2i(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector2i> value) {
            int location = location(uniformName);
            if (location != -1) {
                add(frequency, new Vector2IntUniform(location, value));
            }
            return this;
        }

        public Builder uniform3f(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector3f> value) {
            int location = location(uniformName);
            if (location != -1) {
                add(frequency, new Vector3Uniform(location, value));
            }
            return this;
        }

        public Builder uniform4f(UniformUpdateFrequency frequency, String uniformName, Supplier<Vector4f> value) {
            int location = location(uniformName);
            if (location != -1) {
                add(frequency, new Vector4Uniform(location, value));
            }
            return this;
        }

        public Builder uniformMatrix(UniformUpdateFrequency frequency, String uniformName, Supplier<Matrix4fc> value) {
            int location = location(uniformName);
            if (location != -1) {
                add(frequency, new MatrixUniform(location, value));
            }
            return this;
        }

        public ProgramUniforms buildUniforms() {
            return new ProgramUniforms(this.once, this.perFrame);
        }
    }
}
