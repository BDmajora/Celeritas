package com.bdmajora.impetus.engine.impl.gl.shader;

import com.bdmajora.impetus.engine.impl.gl.shader.uniform.GlUniform;
import com.bdmajora.impetus.engine.impl.gl.shader.uniform.GlUniformBlock;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntFunction;

public interface ShaderBindingContext {
    // Resolves a location and wraps it; throws if the program lacks the uniform
    default <U extends GlUniform<?>> U bindUniform(String name, IntFunction<U> factory) {
        var uniform = bindUniformIfPresent(name, factory);

        if (uniform == null) {
            throw new NullPointerException("No uniform exists with name: " + name);
        }

        return uniform;
    }

    <U extends GlUniform<?>> @Nullable U bindUniformIfPresent(String name, IntFunction<U> factory);

    // Resolves a block index and assigns its binding point
    default GlUniformBlock bindUniformBlock(String name, int bindingPoint) {
        var block = bindUniformBlockIfPresent(name, bindingPoint);

        if (block == null) {
            throw new NullPointerException("No uniform block exists with name: " + name);
        }

        return block;
    }

    @Nullable GlUniformBlock bindUniformBlockIfPresent(String name, int bindingPoint);
}
