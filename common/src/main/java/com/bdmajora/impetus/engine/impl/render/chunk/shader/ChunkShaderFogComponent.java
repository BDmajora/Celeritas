package com.bdmajora.impetus.engine.impl.render.chunk.shader;

import com.bdmajora.impetus.engine.impl.gl.shader.ShaderBindingContext;
import com.bdmajora.impetus.engine.impl.gl.shader.uniform.GlUniformFloat;
import com.bdmajora.impetus.engine.impl.gl.shader.uniform.GlUniformFloat4v;
import com.bdmajora.impetus.engine.impl.gl.shader.uniform.GlUniformInt;
import com.bdmajora.impetus.engine.impl.render.chunk.fog.FogService;

import java.util.ServiceLoader;

// Fixed-function fog reproduced in the chunk shaders from live GL fog state, using camera distance instead of z so fog does not shift with head turns (what Minecraft wants from NVIDIA-only NV_fog_distance)
public abstract class ChunkShaderFogComponent implements ChunkShaderComponent {
    public static final FogService FOG_SERVICE = ServiceLoader.load(FogService.class).findFirst().orElseThrow();

    public static class None extends ChunkShaderFogComponent {
        public None(ShaderBindingContext context) {

        }

        // Uploads this mode's fog uniforms
        @Override
        public void setup() {

        }
    }

    public static class Exp2 extends ChunkShaderFogComponent {
        private final GlUniformFloat4v uFogColor;
        private final GlUniformFloat uFogDensity;

        public Exp2(ShaderBindingContext context) {
            this.uFogColor = context.bindUniform("u_FogColor", GlUniformFloat4v::new);
            this.uFogDensity = context.bindUniform("u_FogDensity", GlUniformFloat::new);
        }

        // Uploads this mode's fog uniforms
        @Override
        public void setup() {
            this.uFogColor.set(FOG_SERVICE.getFogColor());
            this.uFogDensity.set(FOG_SERVICE.getFogDensity());
        }
    }

    public static class Smooth extends ChunkShaderFogComponent {
        private final GlUniformFloat4v uFogColor;

        private final GlUniformInt uFogShape;
        private final GlUniformFloat uFogStart;
        private final GlUniformFloat uFogEnd;

        public Smooth(ShaderBindingContext context) {
            this.uFogColor = context.bindUniform("u_FogColor", GlUniformFloat4v::new);
            this.uFogShape = context.bindUniform("u_FogShape", GlUniformInt::new);
            this.uFogStart = context.bindUniform("u_FogStart", GlUniformFloat::new);
            this.uFogEnd = context.bindUniform("u_FogEnd", GlUniformFloat::new);
        }

        // Uploads this mode's fog uniforms
        @Override
        public void setup() {
            this.uFogColor.set(FOG_SERVICE.getFogColor());
            this.uFogShape.set(FOG_SERVICE.getFogShapeIndex());

            this.uFogStart.set(FOG_SERVICE.getFogStart());
            this.uFogEnd.set(FOG_SERVICE.getFogEnd());
        }
    }

}
